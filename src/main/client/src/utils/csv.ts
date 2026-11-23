// Spreadsheet programs run text that starts with = + - @ as a formula. Driver names are chosen by players, so
// such cells get a leading single quote, which makes the spreadsheet treat them as plain text.
const guard = (value: string) => (/^[=+\-@\t\r]/.test(value) ? `'${value}` : value);

const cell = (value: string | number | null | undefined) => {
	const text = guard(value === null || value === undefined ? '' : String(value));
	return /[",\n\r]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
};

export const toCsv = (rows: Array<Array<string | number | null | undefined>>) => rows.map(r => r.map(cell).join(',')).join('\r\n') + '\r\n';

// Saves the rows as a .csv file through the browser (UTF-8 with a byte order mark so Excel reads names correctly)
export const downloadCsv = (filename: string, rows: Array<Array<string | number | null | undefined>>) => {
	const blob = new Blob(['﻿', toCsv(rows)], {type: 'text/csv;charset=utf-8'});
	const url = URL.createObjectURL(blob);
	const link = document.createElement('a');
	link.href = url;
	link.download = filename;
	document.body.appendChild(link);
	link.click();
	link.remove();
	URL.revokeObjectURL(url);
};
