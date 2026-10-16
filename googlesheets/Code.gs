/**
 * Daily — Google Sheets sync backend.
 *
 * Setup (one time, ~5 minutes):
 *  1. Create a new Google Sheet (sheets.new).
 *  2. Extensions → Apps Script. Delete the sample code, paste this file.
 *  3. Change TOKEN below to a long random string.
 *  4. Deploy → New deployment → type "Web app":
 *       - Execute as: Me
 *       - Who has access: Anyone
 *     Click Deploy, authorize, and copy the web app URL (ends in /exec).
 *  5. In the app: Settings → Google Sheets sync → paste the URL and the
 *     same token → Save & sync.
 *
 * The script creates three tabs on first sync: "Tasks", "DayNotes" and
 * "Baby". You can edit rows in the sheet (title, done, note, times…) — an
 * onEdit trigger stamps updatedAt so your edits sync back to the phone.
 * Don't edit the uuid column.
 *
 * Baby rows carry full ISO-8601 timestamps with an offset
 * ("2026-10-16T14:20:00+01:00") because a feed is an absolute moment, not a
 * wall-clock time like a task's. Those columns are forced to plain text so
 * Sheets cannot quietly turn them into its own date values; if you edit one
 * by hand, an offset, a "Z" time or a plain "2026-10-16 14:20" all parse.
 * durationMin is derived on each push and ignored on the way back — edit
 * startAt/endAt instead. An empty endAt on a feed or sleep means it is
 * still running.
 */

const TOKEN = 'CHANGE_ME_TO_A_LONG_RANDOM_STRING';

const TASK_SHEET = 'Tasks';
const NOTE_SHEET = 'DayNotes';
const BABY_SHEET = 'Baby';
const TASK_HEADERS = ['uuid', 'dayDate', 'title', 'done', 'timed', 'startTime',
  'durationMin', 'note', 'sortOrder', 'createdAt', 'createdBy', 'updatedAt', 'deleted'];
const NOTE_HEADERS = ['dayDate', 'content', 'updatedAt'];
const BABY_HEADERS = ['uuid', 'kind', 'startAt', 'endAt', 'amountMl', 'note',
  'babyId', 'durationMin', 'updatedAt', 'deleted'];

/** Columns Sheets must not reinterpret as its own dates. */
const TEXT_COLUMNS = { 'Baby': ['uuid', 'startAt', 'endAt'] };

function doGet() {
  return ContentService.createTextOutput('Daily sync endpoint is up.');
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const body = JSON.parse(e.postData.contents);
    if (body.token !== TOKEN) {
      return json({ error: 'unauthorized' });
    }
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const tasks = getOrCreateSheet(ss, TASK_SHEET, TASK_HEADERS);
    const notes = getOrCreateSheet(ss, NOTE_SHEET, NOTE_HEADERS);
    const baby = getOrCreateSheet(ss, BABY_SHEET, BABY_HEADERS);
    const since = Number(body.since || 0);

    upsert(tasks, TASK_HEADERS, body.tasks || [], 'uuid');
    upsert(notes, NOTE_HEADERS, body.dayNotes || [], 'dayDate');
    upsert(baby, BABY_HEADERS, body.babyEvents || [], 'uuid');

    return json({
      serverTime: Date.now(),
      tasks: changedSince(tasks, TASK_HEADERS, since),
      dayNotes: changedSince(notes, NOTE_HEADERS, since),
      babyEvents: changedSince(baby, BABY_HEADERS, since)
    });
  } catch (err) {
    return json({ error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

/** Stamp updatedAt whenever a human edits a data row in the sheet. */
function onEdit(e) {
  const sheet = e.range.getSheet();
  const name = sheet.getName();
  let headers;
  if (name === TASK_SHEET) headers = TASK_HEADERS;
  else if (name === NOTE_SHEET) headers = NOTE_HEADERS;
  else if (name === BABY_SHEET) headers = BABY_HEADERS;
  else return;

  const updCol = headers.indexOf('updatedAt') + 1;
  if (e.range.getColumn() === updCol && e.range.getNumColumns() === 1) return;

  const startRow = Math.max(e.range.getRow(), 2);
  const endRow = e.range.getRow() + e.range.getNumRows() - 1;
  const numRows = endRow - startRow + 1;
  if (numRows <= 0) return;

  const now = Date.now();
  const values = Array.from({ length: numRows }, function () { return [now]; });
  sheet.getRange(startRow, updCol, numRows, 1).setValues(values);
}

// ---------------------------------------------------------------- helpers

function getOrCreateSheet(ss, name, headers) {
  let sheet = ss.getSheetByName(name);
  if (!sheet) {
    sheet = ss.insertSheet(name);
    sheet.appendRow(headers);
    sheet.setFrozenRows(1);
  }
  applyTextColumns(sheet, name, headers);
  return sheet;
}

/**
 * Force the timestamp and id columns to plain text. Without this, Sheets
 * parses "2026-10-16T14:20:00+01:00" into a Date and hands back something
 * the app has to guess at; as text it round-trips verbatim.
 */
function applyTextColumns(sheet, name, headers) {
  const columns = TEXT_COLUMNS[name];
  if (!columns) return;
  const rows = Math.max(sheet.getMaxRows() - 1, 1);
  columns.forEach(function (header) {
    const col = headers.indexOf(header) + 1;
    if (col > 0) sheet.getRange(2, col, rows, 1).setNumberFormat('@');
  });
}

/** Last-write-wins upsert keyed by keyName. */
function upsert(sheet, headers, incoming, keyName) {
  if (!incoming.length) return;
  const data = sheet.getDataRange().getValues();
  const keyIdx = headers.indexOf(keyName);
  const updIdx = headers.indexOf('updatedAt');
  const index = {};
  for (let r = 1; r < data.length; r++) {
    index[normalize(data[r][keyIdx], keyName)] = r + 1; // 1-based sheet row
  }
  incoming.forEach(function (obj) {
    const key = String(obj[keyName]);
    const row = headers.map(function (h) {
      return obj[h] === undefined || obj[h] === null ? '' : obj[h];
    });
    const existingRow = index[key];
    if (existingRow) {
      const existingUpd = Number(data[existingRow - 1][updIdx]) || 0;
      if (Number(obj.updatedAt) > existingUpd) {
        sheet.getRange(existingRow, 1, 1, headers.length).setValues([row]);
      }
    } else {
      sheet.appendRow(row);
      index[key] = sheet.getLastRow();
    }
  });
}

/** All rows whose updatedAt is newer than `since`, as JSON objects. */
function changedSince(sheet, headers, since) {
  const data = sheet.getDataRange().getValues();
  const updIdx = headers.indexOf('updatedAt');
  const out = [];
  for (let r = 1; r < data.length; r++) {
    const upd = Number(data[r][updIdx]) || 0;
    if (upd > since) {
      const obj = {};
      headers.forEach(function (h, c) { obj[h] = normalize(data[r][c], h); });
      out.push(obj);
    }
  }
  return out;
}

/**
 * Sheets auto-converts "2026-07-13" and "09:00" cells into Dates; convert
 * them back to the plain strings the app expects.
 */
function normalize(value, header) {
  if (value instanceof Date) {
    const tz = Session.getScriptTimeZone();
    if (header === 'startTime') return Utilities.formatDate(value, tz, 'HH:mm');
    // Baby timestamps are absolute moments. If a cell slipped through as a
    // Date (an older sheet, or a paste that bypassed the text format), send
    // it back with an explicit offset rather than a bare date.
    if (header === 'startAt' || header === 'endAt') {
      return Utilities.formatDate(value, tz, "yyyy-MM-dd'T'HH:mm:ssXXX");
    }
    return Utilities.formatDate(value, tz, 'yyyy-MM-dd');
  }
  return value;
}

function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
