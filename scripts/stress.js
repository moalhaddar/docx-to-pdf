async function start() {

	const requests_no = document.querySelector("input[name='concurrent requests no']");	
	const file = document.querySelector("input[type=file]");
	const start_button = document.querySelector("button[name=start]");
	const results = document.querySelector("div[name=results]");

	var formdata = new FormData();
	
	if (file.files.length < 1) {
		alert("No file was selected!");
		return;
	}
	formdata.append("document", file.files[0], "Test File");

	
	if (!requests_no.value.match(/^[0-9]+$/)) {
		alert("requests_no must be an integer.");
		return;
	}

	start_button.innerHTML = 'Processing';
	results.innerHTML = '';
	start_button.setAttribute("disabled", true);
	
	
	const promises =  []
	const startTime = new Date();
	let success = 0;
	let fail = 0;
	for (let i = 0; i < parseInt(requests_no.value); i++) {
		const p = fetch("http://localhost:8080/docx-to-pdf", {
			method: 'POST',
			body: formdata,
			redirect: 'follow'
			})
			.then(response => success++)
			.catch(error => fail++);

		promises.push(p)
	}

	await Promise.allSettled(promises);

	const endTime = new Date();

	results.innerHTML = `Time: ~${endTime - startTime}ms, Success: ${success}, Failed: ${fail}`

	start_button.innerHTML = 'Start';
	start_button.removeAttribute("disabled");
}
