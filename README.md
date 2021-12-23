# Doxc to PDF converter
The problem: too many tools to convert from docx to PDF, but usually they end up messing something up in the formating, handling RTL text, handling different fonts and so on. This library is built right on top of LibreOffice, and provides an HTTP API that allows uploading a *.docx file and respondes with the *.pdf file back.


# Usage
You will need docker and docker compose to run this tool.

```sh
# In the root of this repositry after cloning
$ docker-compose up -d --build
```

After building the image and upping the container, you can inspect the logs by running
```sh
docker-compose logs -tf --tail=100 pdf_generator
```

The api will be exposed at `localhost:9999/docx-to-pdf`

## Example Request (cURL)
```sh
curl --location --request POST 'http://localhost:9999/docx-to-pdf' \
--form 'document=@"/C:/contract.docx"'
```

## Example Request (JavaScript)
```js
const formdata = new FormData();
formdata.append("document", fileInput.files[0], "/C:/contract.docx");

const requestOptions = {
  method: 'POST',
  body: formdata,
  redirect: 'follow'
};

fetch("http://localhost:9999/docx-to-pdf", requestOptions)
  .then(response => response.text())
  .then(result => console.log(result))
  .catch(error => console.log('error', error));
```

## Response
A pdf file (`application/pdf`)

# FAQ
- The generated PDF fonts are not proper, what went wrong?

Most probably the font you are using is not included within the build, you will need to add your custom fonts within the `./fonts` folder directly (both ttf and otf should work)

- Some shapes are not in their place, why is this happening?

Sadly, this is an artifact from LibreOffice, you might want to make your shapes to be inline (following text) and not floating around as a workaround.


# License
MIT License

# Author
Mohammed Alhaddar