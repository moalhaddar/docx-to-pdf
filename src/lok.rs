use std::ffi::{CStr, CString, c_char, c_int, c_void};
use std::fs::File;
use std::io::{BufRead, BufReader, Write};
use std::os::fd::FromRawFd;
use std::path::{Path, PathBuf};
use std::ptr;

const WARMUP_DOCX: &[u8] = include_bytes!("../examples/ex1/input.docx");
const LOK_LIBS: [&str; 2] = ["libsofficeapp.so", "libmergedlo.so"];

const LOCKDOWN_XCU: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<oor:items xmlns:oor="http://openoffice.org/2001/registry" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <item oor:path="/org.openoffice.Office.Common/Security/Scripting">
    <prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop>
  </item>
  <item oor:path="/org.openoffice.Office.Writer/Content/Update">
    <prop oor:name="Link" oor:op="fuse"><value>0</value></prop>
  </item>
</oor:items>
"#;

#[repr(C)]
struct LibreOfficeKit {
    p_class: *const LibreOfficeKitClass,
}

#[repr(C)]
struct LibreOfficeKitClass {
    n_size: usize,
    destroy: unsafe extern "C" fn(*mut LibreOfficeKit),
    document_load:
        unsafe extern "C" fn(*mut LibreOfficeKit, *const c_char) -> *mut LibreOfficeKitDocument,
    get_error: unsafe extern "C" fn(*mut LibreOfficeKit) -> *mut c_char,
    document_load_with_options: unsafe extern "C" fn(
        *mut LibreOfficeKit,
        *const c_char,
        *const c_char,
    ) -> *mut LibreOfficeKitDocument,
    free_error: Option<unsafe extern "C" fn(*mut c_char)>,
}

#[repr(C)]
struct LibreOfficeKitDocument {
    p_class: *const LibreOfficeKitDocumentClass,
}

#[repr(C)]
struct LibreOfficeKitDocumentClass {
    n_size: usize,
    destroy: unsafe extern "C" fn(*mut LibreOfficeKitDocument),
    save_as: unsafe extern "C" fn(
        *mut LibreOfficeKitDocument,
        *const c_char,
        *const c_char,
        *const c_char,
    ) -> c_int,
}

type LokHook2 = unsafe extern "C" fn(*const c_char, *const c_char) -> *mut LibreOfficeKit;

struct LokEngine {
    kit: *mut LibreOfficeKit,
}

impl LokEngine {
    fn init(program_dir: &Path, profile_dir: &Path) -> Result<Self, String> {
        let so_path = LOK_LIBS
            .iter()
            .map(|name| program_dir.join(name))
            .find(|p| p.is_file())
            .ok_or_else(|| {
                format!(
                    "neither libsofficeapp.so nor libmergedlo.so found in {}",
                    program_dir.display()
                )
            })?;
        let so_cstr = path_to_cstring(&so_path)?;
        let prog_cstr = path_to_cstring(program_dir)?;
        let profile_url = format!("file://{}", profile_dir.join("user").display());
        let profile_cstr = CString::new(profile_url).map_err(|e| e.to_string())?;

        unsafe {
            let handle = libc::dlopen(so_cstr.as_ptr(), libc::RTLD_LAZY | libc::RTLD_GLOBAL);
            if handle.is_null() {
                return Err(format!(
                    "dlopen({}) failed: {}",
                    so_path.display(),
                    dlerror_string()
                ));
            }
            let sym = libc::dlsym(handle, c"libreofficekit_hook_2".as_ptr());
            if sym.is_null() {
                return Err(format!(
                    "dlsym(libreofficekit_hook_2) failed: {}",
                    dlerror_string()
                ));
            }
            let hook: LokHook2 = std::mem::transmute::<*mut c_void, LokHook2>(sym);
            let kit = hook(prog_cstr.as_ptr(), profile_cstr.as_ptr());
            if kit.is_null() {
                return Err("libreofficekit_hook_2 returned NULL".to_string());
            }
            Ok(Self { kit })
        }
    }

    fn convert(&self, in_path: &str, out_path: &str) -> Result<(), String> {
        let in_cstr = CString::new(in_path).map_err(|e| e.to_string())?;
        let out_cstr = CString::new(out_path).map_err(|e| e.to_string())?;

        unsafe {
            let kit_cls = &*(*self.kit).p_class;
            let doc = (kit_cls.document_load)(self.kit, in_cstr.as_ptr());
            if doc.is_null() {
                return Err(format!("documentLoad failed: {}", self.take_error()));
            }
            let doc_cls = &*(*doc).p_class;
            let ok = (doc_cls.save_as)(doc, out_cstr.as_ptr(), c"pdf".as_ptr(), ptr::null());
            (doc_cls.destroy)(doc);
            if ok == 0 {
                return Err(format!("saveAs failed: {}", self.take_error()));
            }
            Ok(())
        }
    }

    unsafe fn take_error(&self) -> String {
        unsafe {
            let kit_cls = &*(*self.kit).p_class;
            let err_ptr = (kit_cls.get_error)(self.kit);
            if err_ptr.is_null() {
                return "unknown LibreOfficeKit error".to_string();
            }
            let msg = CStr::from_ptr(err_ptr).to_string_lossy().into_owned();
            if let Some(free_fn) = kit_cls.free_error {
                free_fn(err_ptr);
            }
            msg
        }
    }
}

pub fn run_worker(worker_id: usize, expected_ppid: u32, profile_dir: &Path) -> ! {
    // Die immediately if the supervisor process ever exits, and verify the parent
    // did not already exit between fork() and prctl().
    #[cfg(target_os = "linux")]
    unsafe {
        libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL);
        if expected_ppid != 0 && libc::getppid() as u32 != expected_ppid {
            libc::_exit(1);
        }
    }

    // Reserve original stdout (fd 1) exclusively for the supervisor RPC protocol
    // with FD_CLOEXEC so no subprocess spawned by LibreOffice can inherit the pipe,
    // and redirect stdout -> stderr so any C/C++ logging inside LibreOffice goes to stderr.
    let rpc_fd = unsafe { libc::fcntl(libc::STDOUT_FILENO, libc::F_DUPFD_CLOEXEC, 3) };
    if rpc_fd < 0 {
        eprintln!("[LibreOffice/{worker_id}] fcntl(F_DUPFD_CLOEXEC) failed");
        unsafe { libc::_exit(1) };
    }
    unsafe {
        libc::dup2(libc::STDERR_FILENO, libc::STDOUT_FILENO);
    }
    let mut rpc_out = unsafe { File::from_raw_fd(rpc_fd) };

    let program_dir = match locate_program_dir() {
        Ok(dir) => dir,
        Err(e) => {
            eprintln!("[LibreOffice/{worker_id}] {e}");
            unsafe { libc::_exit(1) };
        }
    };

    let cache_dir = profile_dir.join("cache");
    let config_dir = profile_dir.join("config");
    let tmp_dir = profile_dir.join("tmp");
    let user_profile_dir = profile_dir.join("user").join("user");
    let _ = std::fs::create_dir_all(&cache_dir);
    let _ = std::fs::create_dir_all(&config_dir);
    let _ = std::fs::create_dir_all(&tmp_dir);
    let _ = std::fs::create_dir_all(&user_profile_dir);
    let _ = std::fs::write(
        user_profile_dir.join("registrymodifications.xcu"),
        LOCKDOWN_XCU,
    );

    // SAFETY: Worker is single-threaded here before loading libsofficeapp.so.
    unsafe {
        std::env::set_var(
            "URE_BOOTSTRAP",
            format!(
                "vnd.sun.star.pathname:{}/fundamentalrc",
                program_dir.display()
            ),
        );
        std::env::set_var("HOME", profile_dir);
        std::env::set_var("USER", "app");
        std::env::set_var("LOGNAME", "app");
        std::env::set_var("XDG_CACHE_HOME", &cache_dir);
        std::env::set_var("XDG_CONFIG_HOME", &config_dir);
        std::env::set_var("TMPDIR", &tmp_dir);
        std::env::set_var("SAL_USE_VCLPLUGIN", "svp");
        std::env::set_var("SAL_ENABLE_FILE_LOCKING", "0");
    }

    // Apply before LibreOffice creates threads so they inherit the available
    // policy. Sandboxing is best-effort to preserve startup on older hosts.
    if let Err(e) = crate::sandbox::restrict_worker(&program_dir, profile_dir) {
        eprintln!(
            "[LibreOffice/{worker_id}] Filesystem sandbox could not be applied: {e}; continuing without Landlock filesystem isolation"
        );
    }

    let engine = match LokEngine::init(&program_dir, profile_dir) {
        Ok(eng) => eng,
        Err(e) => {
            eprintln!("[LibreOffice/{worker_id}] Init failed: {e}");
            unsafe { libc::_exit(1) };
        }
    };

    // Pre-warm the OOXML import + Writer layout + PDF export shared libraries before signaling READY.
    let warm_in = profile_dir.join("warmup.docx");
    let warm_out = profile_dir.join("warmup.pdf");
    if std::fs::write(&warm_in, WARMUP_DOCX).is_ok() {
        if let Err(e) = engine.convert(&warm_in.to_string_lossy(), &warm_out.to_string_lossy()) {
            eprintln!("[LibreOffice/{worker_id}] Warmup failed: {e}");
            unsafe { libc::_exit(1) };
        }
        let _ = std::fs::remove_file(&warm_in);
        let _ = std::fs::remove_file(&warm_out);
    }

    if writeln!(rpc_out, "READY")
        .and_then(|_| rpc_out.flush())
        .is_err()
    {
        unsafe { libc::_exit(1) };
    }

    let stdin = std::io::stdin();
    for line in BufReader::new(stdin.lock()).lines() {
        let Ok(line) = line else { break };
        let Some((in_path, out_path)) = line.split_once('\t') else {
            continue;
        };
        let reply = match engine.convert(in_path, out_path) {
            Ok(()) => "OK\n".to_string(),
            Err(e) => {
                let sanitized = e.replace(['\r', '\n'], " ");
                format!("ERR\t{sanitized}\n")
            }
        };
        if rpc_out
            .write_all(reply.as_bytes())
            .and_then(|_| rpc_out.flush())
            .is_err()
        {
            break;
        }
    }

    // Use _exit(0) so glibc does not run LibreOffice's C++ static destructors
    // while its background threads (lo_startmain, configmgrWriter) are parked.
    unsafe { libc::_exit(0) }
}

fn has_lok_lib(dir: &Path) -> bool {
    LOK_LIBS.iter().any(|name| dir.join(name).is_file())
}

fn locate_program_dir() -> Result<PathBuf, String> {
    if let Ok(custom) = std::env::var("LO_PROGRAM_DIR") {
        let p = PathBuf::from(custom);
        if has_lok_lib(&p) {
            return Ok(p);
        }
    }
    for candidate in [
        "/usr/lib/libreoffice/program",
        "/usr/lib64/libreoffice/program",
    ] {
        let p = PathBuf::from(candidate);
        if has_lok_lib(&p) {
            return Ok(p);
        }
    }
    for bin in ["/usr/bin/libreoffice", "/usr/bin/soffice"] {
        if let Ok(real) = std::fs::canonicalize(bin)
            && let Some(parent) = real.parent()
            && has_lok_lib(parent)
        {
            return Ok(parent.to_path_buf());
        }
    }
    Err("Could not locate LibreOffice program directory containing libsofficeapp.so or libmergedlo.so".to_string())
}

fn path_to_cstring(p: &Path) -> Result<CString, String> {
    CString::new(p.to_string_lossy().as_bytes()).map_err(|e| e.to_string())
}

unsafe fn dlerror_string() -> String {
    unsafe {
        let err = libc::dlerror();
        if err.is_null() {
            "unknown dlerror".to_string()
        } else {
            CStr::from_ptr(err).to_string_lossy().into_owned()
        }
    }
}
