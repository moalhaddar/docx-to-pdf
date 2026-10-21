use std::path::Path;

#[cfg(target_os = "linux")]
pub fn restrict_worker(program_dir: &Path, profile_dir: &Path) -> Result<bool, String> {
    use landlock::{
        ABI, Access, AccessFs, CompatLevel, Compatible, PathBeneath, PathFd, Ruleset, RulesetAttr,
        RulesetCreatedAttr, RulesetStatus,
    };

    // Request ABI 3 (including truncate), using only the supported rights on
    // older hosts. Keep conversion available when Landlock is unavailable.
    let abi = ABI::V3;
    let read = AccessFs::from_read(abi);
    let write = read | AccessFs::from_write(abi);
    let mut ruleset = Ruleset::default()
        .set_compatibility(CompatLevel::BestEffort)
        .handle_access(AccessFs::from_all(abi))
        .map_err(|e| e.to_string())?
        .create()
        .map_err(|e| e.to_string())?;

    // Only engine resources, installed fonts and selected OS configuration are
    // readable. Do not grant /tmp, /home, /run or all of /etc: these can contain
    // other jobs, mounted secrets and unrelated application data.
    let resources = [
        Path::new("/usr/lib"),
        Path::new("/usr/share"),
        Path::new("/lib"),
        Path::new("/lib64"),
        Path::new("/etc/fonts"),
        Path::new("/etc/libreoffice"),
        Path::new("/etc/ld.so.cache"),
        Path::new("/etc/localtime"),
        Path::new("/etc/nsswitch.conf"),
        Path::new("/etc/passwd"),
        Path::new("/etc/group"),
        Path::new("/var/cache/fontconfig"),
        Path::new("/proc/self"),
        Path::new("/proc/meminfo"),
        Path::new("/proc/cpuinfo"),
        Path::new("/dev/urandom"),
        Path::new("/dev/random"),
        program_dir,
    ];
    for path in resources {
        if !path.try_exists().map_err(|e| e.to_string())? {
            continue;
        }
        ruleset = ruleset
            .add_rule(PathBeneath::new(
                PathFd::new(path).map_err(|e| e.to_string())?,
                if path.is_dir() {
                    read
                } else {
                    AccessFs::ReadFile.into()
                },
            ))
            .map_err(|e| e.to_string())?;
    }
    for path in [profile_dir, Path::new("/dev/null")] {
        ruleset = ruleset
            .add_rule(PathBeneath::new(
                PathFd::new(path).map_err(|e| e.to_string())?,
                if path.is_dir() {
                    write
                } else {
                    AccessFs::ReadFile | AccessFs::WriteFile | AccessFs::Truncate
                },
            ))
            .map_err(|e| e.to_string())?;
    }
    let status = ruleset.restrict_self().map_err(|e| e.to_string())?;
    let fully_enforced = status.ruleset == RulesetStatus::FullyEnforced && status.no_new_privs;
    if !fully_enforced {
        eprintln!(
            "[Sandbox] Best-effort filesystem protection: {:?}, no_new_privs={}; continuing with reduced protection",
            status.ruleset, status.no_new_privs
        );
    }
    Ok(fully_enforced)
}

#[cfg(not(target_os = "linux"))]
pub fn restrict_worker(_program_dir: &Path, _profile_dir: &Path) -> Result<bool, String> {
    eprintln!(
        "[Sandbox] Landlock is unavailable on this platform; continuing without filesystem isolation"
    );
    Ok(false)
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;
    use std::process::Command;

    #[test]
    fn worker_cannot_read_other_jobs_or_follow_escape_symlinks() {
        // Landlock is irreversible; enforce it only in a dedicated subprocess.
        if std::env::var_os("DOCX_SANDBOX_TEST_HELPER").is_none() {
            let profile = tempfile::tempdir().unwrap();
            let other_job = tempfile::tempdir().unwrap();
            let secret = other_job.path().join("secret.txt");
            std::fs::write(&secret, "private marker").unwrap();
            symlink(&secret, profile.path().join("escape")).unwrap();
            let status = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "sandbox::tests::worker_cannot_read_other_jobs_or_follow_escape_symlinks",
                    "--nocapture",
                ])
                .env("DOCX_SANDBOX_TEST_HELPER", "1")
                .env("DOCX_SANDBOX_TEST_PROFILE", profile.path())
                .env("DOCX_SANDBOX_TEST_OTHER", other_job.path())
                .status()
                .unwrap();
            assert!(status.success());
            return;
        }
        let profile =
            std::path::PathBuf::from(std::env::var_os("DOCX_SANDBOX_TEST_PROFILE").unwrap());
        let other_job =
            std::path::PathBuf::from(std::env::var_os("DOCX_SANDBOX_TEST_OTHER").unwrap());
        let secret = other_job.join("secret.txt");
        let fully_enforced = restrict_worker(Path::new("/usr/lib"), &profile).unwrap();
        let own_file = profile.join("input.docx");
        std::fs::write(&own_file, "own job").unwrap();
        assert_eq!(std::fs::read_to_string(own_file).unwrap(), "own job");
        if !fully_enforced {
            eprintln!("Skipping full-policy denial assertions: Landlock is not fully available");
            return;
        }
        for path in [secret, profile.join("escape")] {
            assert_eq!(
                std::fs::read(path).unwrap_err().kind(),
                std::io::ErrorKind::PermissionDenied
            );
        }
        assert!(std::fs::write(other_job.join("output"), "denied").is_err());
    }
}
