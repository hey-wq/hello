# Customs desk

Working name. A review desk for customs brokers: it reads an invoice and
packing list, drafts the import declaration, checks it, and keeps a record of
what the declarant did about every problem it found.

This is a first version for testing with pilot brokers. It does not file
anything with a customs authority.

## What it does

1. **Reads the documents.** Invoice and packing list (PDF or image) are sent
   to Claude, which transcribes them into structured data and notes anything
   illegible or contradictory. A cheaper model reads first. The documents are
   read again by the stronger model if the first reading reports doubt, is
   missing a required field, or contains figures that do not add up. The
   record shows which model read the documents and why.
2. **Drafts the declaration.** Parties, terms, items, weights, and a customs
   value per item in euro (invoice value, plus freight and insurance
   apportioned by weight where the delivery term excludes them).
3. **Proposes commodity codes** with the reasoning and a confidence level.
   Codes the office has confirmed before for the same importer and goods are
   reused. Every code must be confirmed by the declarant.
4. **Runs checks.**
   - Documents: line arithmetic, totals, invoice against packing list.
   - Completeness: EORI, origin, weights, exchange rate, freight, 10-digit code.
   - Value: declared euro per kg against a reference table and against the
     office's own approved history.
   - Importer: first-time importer, escalated when combined with a low value.
5. **Requires a resolution for every flag.** A declaration cannot be approved
   while a flag is open or a code is unconfirmed. If the facts behind a
   resolved flag change, it reopens.
6. **Keeps the record.** Every document, proposal, edit, flag, resolution and
   decision is logged with who and when. Entries are hash-chained, so a later
   alteration is detectable. The record downloads as a PDF.

## Run it

```
pip install -r requirements.txt
python -m customsdesk.auth add maria "Maria Papadopoulou"   # create a user
export ANTHROPIC_API_KEY=...        # needed to read real documents
uvicorn --factory customsdesk.api:create_app --port 8000
```

Open http://localhost:8000 and sign in. Without an API key, uploads are
switched off and "Load sample shipment" shows the review flow on made-up data.

## Users

Each declarant has their own account, and the record attributes every action
to the signed-in user. Add or reset a user with
`python -m customsdesk.auth add <username> "<Full name>"`; passwords need at
least 10 characters. Sessions last 12 hours. Five wrong passwords lock that
username for a minute. There are no roles yet: every user can see and act on
every shipment.

## Install it as an app

Once the tool is hosted on an https address, it installs like an app, with its
own icon and no browser bars:

- **iPhone or iPad**: open the address in Safari, tap Share, then "Add to Home
  Screen".
- **Android**: open it in Chrome, tap the menu, then "Install app".
- **Desktop Chrome or Edge**: click the install icon in the address bar.

The app keeps no shipment data on the device. Everything is loaded from the
server each time, so it needs a connection to do anything.

## Host it

The `Dockerfile` packages the tool for any host that runs containers (Render,
Fly.io, Railway, a VPS). The host needs:

- a persistent disk mounted at `/data`, or everything is lost on each deploy
- https, which those platforms provide
- the settings below, with the first user created from the three
  `CUSTOMSDESK_ADMIN_*` variables on first start

```
docker build -t customsdesk .
docker run -p 8000:8000 -v customsdesk-data:/data \
  -e ANTHROPIC_API_KEY=... \
  -e CUSTOMSDESK_ADMIN_USER=maria -e CUSTOMSDESK_ADMIN_NAME="Maria Papadopoulou" \
  -e CUSTOMSDESK_ADMIN_PASSWORD=... customsdesk
```

Run a single instance: the database is a file on that disk.

Settings (environment variables):

| Variable | Default | Purpose |
|---|---|---|
| `CUSTOMSDESK_DATA` | `./var` | Database and uploaded documents |
| `CUSTOMSDESK_BENCHMARKS` | `data/benchmarks_sample.csv` | Reference unit values |
| `CUSTOMSDESK_ADMIN_USER`, `_NAME`, `_PASSWORD` | none | Creates the first user if there are none |
| `CUSTOMSDESK_READ_MODEL` | `claude-sonnet-5-5` | First reading of the documents |
| `CUSTOMSDESK_STRONG_MODEL` | `claude-opus-5-5` | Second reading when needed, and all code proposals |

Set both model variables to the same value to switch the second reading off.

Tests: `pip install -r requirements-dev.txt`, then `python -m pytest`

## Demo page

`python demo/build.py` writes `demo/customs-desk-demo.html`: the real review
screen in a single file, with `demo/backend.js` standing in for the server and
the sample shipment loaded. It needs no server, saves nothing, and works on a
phone. `python demo/parity.py` checks that the stand-in agrees with the real
server; run it after changing the checks.

## Before using it on real shipments

- **Reference values are placeholders.** `data/benchmarks_sample.csv` holds
  invented figures. Replace it with real unit values (for example a Eurostat
  Comext extract) in the same columns.
- **Document reading has not been run against the live API or real
  documents.** The request is covered by a test with a stand-in client only.
  Accuracy has to be measured on real past shipments.
- **Commodity codes are not validated against TARIC.** Only the length is
  checked.
- **The container image has not been built or run.** The Dockerfile is
  written but untested; the tool itself was tested by running it directly.
- **Sign-in is basic.** Passwords and sessions only: no roles, no password
  reset by email, no two-factor. Adequate for a pilot with a few named users.
- **No backups.** The data is one folder; the host has to back it up.
- **Importer documents go to Anthropic's API** for reading. Brokers need to
  agree to that, and it needs covering in the data processing agreement.
- **The sample shipment is fictional**, including its codes and prices.

## Not built yet

- Submission to ICISnet or export in a filing-software format (the export is
  plain JSON).
- VIES and EORI online validation, sanctions screening.
- Attaching the importer's proof (bank transfer, contract) to a resolution.
- Duty and VAT calculation.
- Greek-language interface.
- Customs valuation adjustments beyond freight and insurance.

## Layout

```
customsdesk/
  models.py        data model
  extract.py       document reading and code proposals (Claude)
  declaration.py   draft declaration and customs value
  checks.py        the checks and the reference table
  service.py       declarant actions and what gets recorded
  store.py         SQLite storage and the hash-chained record
  auth.py          user accounts and sessions
  record_pdf.py    the record as a PDF
  api.py           HTTP API
  web/             review screen, app manifest, icons, service worker
```
