![GitHub release (latest by date)](https://img.shields.io/github/v/release/hmrc/upload-documents-frontend) ![GitHub code size in bytes](https://img.shields.io/github/languages/code-size/hmrc/upload-documents-frontend) ![GitHub last commit](https://img.shields.io/github/last-commit/hmrc/upload-documents-frontend)

# upload-documents-frontend

Plug&Play customizable frontend microservice for uploading documents to the Upscan. 

Features:
- UI to upload multiple files on a single page
- non-JS UI version for uploading one file per page
- per-client customization

## Running the tests

    sbt test it:test

## Running the tests with coverage

    sbt clean coverageOn test it:test coverageReport

## Running the app locally

    sm --start UPLOAD_DOCUMENTS_ALL
    sm --stop UPLOAD_DOCUMENTS_FRONTEND 
    sbt run

It should then be listening on port 9379

    browse http://localhost:9379/upload-documents

### License


This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")
