'use strict';
/*
 * The panel page's own code for the Node tests that check it: the source of one of its inline functions, to read or to
 * run under vm. Shared by panel-scripts.test.js and theme-creator.test.js.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

/* index.html as text, without the carriage returns of a Windows checkout. */
const PANEL = fs.readFileSync(path.join(__dirname, '../../main/resources/web/index.html'), 'utf8').replace(/\r/g, '');

/* The source of a function of the panel (from "function name(" to its closing brace). */
function panelFunction(name) {
  const at = PANEL.indexOf('function ' + name + '(');
  assert.ok(at >= 0, name + ' not found');
  let depth = 0;
  for (let i = PANEL.indexOf('{', at); i < PANEL.length; i++) {
    if (PANEL[i] === '{') depth++;
    else if (PANEL[i] === '}' && --depth === 0) return PANEL.slice(at, i + 1);
  }
  throw new Error(name + ' never closes');
}

module.exports = { panelFunction };
