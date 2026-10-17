'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ThemeApply = require('../../main/resources/web/theme-apply.js');
const ThemePalette = require('../../main/resources/web/theme-palette.js');

const INDEX = fs.readFileSync(path.join(__dirname, '../../main/resources/web/index.html'), 'utf8');
const THEME_FILE = fs.readFileSync(
  path.join(__dirname, '../../main/java/com/jagrosh/jmusicbot/theme/ThemeFile.java'), 'utf8');

const FSI = '\u2068';
const PDI = '\u2069';

function javaList(name) {
  const m = new RegExp('List<String> ' + name + ' = list\\(([^;]*)\\);').exec(THEME_FILE);
  assert.ok(m, 'ThemeFile.' + name + ' not found');
  return m[1].match(/"[^"]*"/g).map((s) => s.slice(1, -1));
}

const COLORS = {
  bgPrimary: '#0b0d14', bgSecondary: '#10131c', bgCard: '#161a26', bgElevated: '#1c2131', bgHover: '#262c42',
  accent: '#7c5cff', accentLight: '#a08bff', accent2: '#22d3ee', textPrimary: '#eef0f8',
  textSecondary: '#9aa0b8', textTertiary: '#646a82', border: '#ffffff12', borderLight: '#ffffff1f',
  success: '#34d399', glass: '#10131ce0'
};

function theme(extra) {
  return Object.assign({
    id: 'k3j9x0q2m7ab', name: 'Midnight Drive', owner: { id: '1', name: 'fred' }, visibility: 'public',
    hiddenByOwner: false, mine: false, version: 1, base: 'ember', decorationsOff: [], colors: Object.assign({}, COLORS),
    radiusScale: 100, font: 'Outfit', background: null, mini: null
  }, extra);
}

const PICTURE = '/theme-images/' + '9f2c'.repeat(8) + '.webp';

// ---- the lists must not drift from the stylesheet and the server

test('builtInThemesAreTheStylesheetsAndTheServers', () => {
  const blocks = [...INDEX.matchAll(/^ {8}html\[data-theme="([a-z-]+)"\] \{/gm)].map((m) => m[1]);
  assert.deepEqual([...ThemeApply.BUILT_IN].sort(), ['nebula', ...new Set(blocks)].sort());
  assert.deepEqual([...ThemeApply.BUILT_IN], javaList('BASES'));
});

test('theTinyScriptInTheHeadKnowsTheSameBuiltInThemes', () => {
  const head = /var t = localStorage\.getItem\(k\);[\s\S]*?\[([^\]]*)\]\.indexOf\(t\)/.exec(INDEX);
  assert.ok(head, 'head script not found');
  const listed = [...head[1].matchAll(/'([a-z-]+)'/g)].map((m) => m[1]);
  assert.deepEqual(listed.sort(), ThemeApply.BUILT_IN.filter((id) => id !== 'nebula').sort());
});

test('applyThemeKnowsTheSameBuiltInThemes', () => {
  // the list applyTheme checks against, which the no-scripts fallback for a custom pick checks its stored base against too
  const ok = /var BUILTIN_THEMES = \{([^}]*)\};/.exec(INDEX);
  assert.ok(ok, 'applyTheme list not found');
  const listed = [...ok[1].matchAll(/(?:^|,)\s*'?([a-z-]+)'?\s*:\s*1/g)].map((m) => m[1]);
  assert.deepEqual(listed, [...ThemeApply.BUILT_IN]);
});

test('everyBuiltInThemeIsInThePicker', () => {
  const select = /<select id="themeSelect"[\s\S]*?<\/select>/.exec(INDEX);
  assert.ok(select, 'themeSelect not found');
  const values = [...select[0].matchAll(/<option value="([a-z-]+)"/g)].map((m) => m[1]);
  assert.deepEqual(values, [...ThemeApply.BUILT_IN]);
});

test('everyFontHasAStackLikeTheBuiltInsUse', () => {
  assert.deepEqual(Object.keys(ThemeApply.FONT_STACKS), javaList('FONTS'));
  for (const font of javaList('FONTS')) {
    const stack = ThemeApply.fontStack(font);
    assert.ok(stack.startsWith("'" + font + "', "), stack);
    assert.match(stack, /(sans-serif|serif|monospace)$/);
  }
});

test('radiusVariablesAreEveryOneTheStylesheetDefines', () => {
  const defined = new Set([...INDEX.matchAll(/(--[a-z0-9-]*radius[a-z0-9-]*)\s*:/g)].map((m) => m[1]));
  assert.deepEqual([...ThemeApply.RADIUS_VARS].sort(), [...defined].sort());
});

test('everyRadiusValueTheStylesheetUsesCanBeScaled', () => {
  for (const m of INDEX.matchAll(/--[a-z0-9-]*radius[a-z0-9-]*\s*:\s*([^;]*);/g)) {
    const value = m[1].trim();
    if (/^0(px)?$/.test(value)) continue;
    assert.notEqual(ThemeApply.scaleRadius(value, 50), null, value);
  }
});

// ---- pictures

test('pictureUrlOnlyAcceptsTheSecretLinks', () => {
  const hex = '0123456789abcdef0123456789abcdef';
  for (const ext of ['jpg', 'png', 'webp', 'gif']) {
    const url = '/theme-images/' + hex + '.' + ext;
    assert.equal(ThemeApply.pictureUrl(url), url);
  }
  const bad = [
    '/theme-images/' + hex + '.svg', '/theme-images/' + hex + '.jpeg', '/theme-images/' + hex.toUpperCase() + '.jpg',
    '/theme-images/' + hex.slice(1) + '.jpg', '/theme-images/' + hex + '0.jpg', '/theme-images/' + hex + '.jpg?x=1',
    '/theme-images/' + hex + '.jpg#x', '/theme-images/' + hex + '.jpg\n', ' /theme-images/' + hex + '.jpg',
    'https://evil.example/theme-images/' + hex + '.jpg', '//evil.example/theme-images/' + hex + '.jpg',
    '/theme-images/../' + hex + '.jpg', '/theme-images//' + hex + '.jpg', '/other/' + hex + '.jpg',
    '/theme-images/' + hex + '.jpg"); background: url(//evil', 'javascript:alert(1)', 'data:image/png;base64,AAAA',
    '', null, undefined, 42, {}, ['/theme-images/' + hex + '.jpg']
  ];
  for (const url of bad) assert.equal(ThemeApply.pictureUrl(url), null, String(url));
});

test('everyPictureTypeButJpegMayMove', () => {
  const hex = '0123456789abcdef0123456789abcdef';
  assert.equal(ThemeApply.canAnimate('/theme-images/' + hex + '.gif'), true);
  assert.equal(ThemeApply.canAnimate('/theme-images/' + hex + '.webp'), true);
  // the server keeps any PNG as .png, an animated one (APNG) included
  assert.equal(ThemeApply.canAnimate('/theme-images/' + hex + '.png'), true);
  assert.equal(ThemeApply.canAnimate('/theme-images/' + hex + '.jpg'), false);
  assert.equal(ThemeApply.canAnimate(null), false);
  assert.equal(ThemeApply.canAnimate(undefined), false);
  assert.equal(ThemeApply.canAnimate(42), false);
});

test('backdropMapsFitDimAndBlurToStyleValues', () => {
  const b = ThemeApply.backdrop({ url: PICTURE, dim: 60, blur: 6, fit: 'cover' });
  assert.deepEqual(b, {
    url: PICTURE, size: 'cover', position: 'center', repeat: 'no-repeat',
    filter: 'blur(6px)', inset: '-12px', overlay: 'rgba(0, 0, 0, 0.6)'
  });
  const plain = ThemeApply.backdrop({ url: PICTURE, dim: 0, blur: 0, fit: 'contain' });
  assert.equal(plain.size, 'contain');
  assert.equal(plain.filter, 'none');
  assert.equal(plain.inset, '0px');
  assert.equal(plain.overlay, 'rgba(0, 0, 0, 0)');
  assert.equal(ThemeApply.backdrop({ url: PICTURE, dim: 45, blur: 0, fit: 'center' }).size, 'auto');
  assert.equal(ThemeApply.backdrop({ url: PICTURE, dim: 45, blur: 0, fit: 'center' }).overlay, 'rgba(0, 0, 0, 0.45)');
});

test('backdropClampsOutOfRangeValuesAndIgnoresBadPictures', () => {
  assert.equal(ThemeApply.backdrop({ url: PICTURE, dim: 500, blur: 0, fit: 'cover' }).overlay, 'rgba(0, 0, 0, 0.9)');
  assert.equal(ThemeApply.backdrop({ url: PICTURE, dim: -5, blur: 0, fit: 'cover' }).overlay, 'rgba(0, 0, 0, 0)');
  assert.equal(ThemeApply.backdrop({ url: PICTURE, dim: 'x', blur: 'y', fit: 'cover' }).overlay, 'rgba(0, 0, 0, 0)');
  assert.equal(ThemeApply.backdrop({ url: PICTURE, dim: 0, blur: 99, fit: 'cover' }).filter, 'blur(20px)');
  assert.equal(ThemeApply.backdrop({ url: PICTURE, dim: 0, blur: -3, fit: 'cover' }).filter, 'none');
  assert.equal(ThemeApply.backdrop({ url: PICTURE, dim: 0, blur: 0, fit: 'stretch' }).size, 'cover');
  assert.equal(ThemeApply.backdrop({ url: 'https://evil.example/x.png', dim: 0, blur: 0, fit: 'cover' }), null);
  assert.equal(ThemeApply.backdrop(null), null);
  assert.equal(ThemeApply.backdrop({}), null);
});

// ---- the creator's own preview picture (a blob: link the creator made for a file on this computer)

const BLOB = 'blob:https://music.example.org/3f2b8c1e-7a4d-4e59-9b0c-2d6f1a8e4c70';

test('localPictureOnlyAcceptsABlobLinkOfThisPage', () => {
  assert.deepEqual(ThemeApply.localPicture(BLOB, true), { url: BLOB, animated: true });
  assert.deepEqual(ThemeApply.localPicture(BLOB, false), { url: BLOB, animated: false });
  assert.deepEqual(ThemeApply.localPicture(BLOB, undefined), { url: BLOB, animated: false });
  for (const origin of ['http://127.0.0.1:8765', 'https://tauri.localhost', 'http://localhost:3000', 'null']) {
    const url = 'blob:' + origin + '/3f2b8c1e-7a4d-4e59-9b0c-2d6f1a8e4c70';
    assert.notEqual(ThemeApply.localPicture(url, false), null, url);
  }
  const bad = [
    'blob:https://music.example.org/3f2b8c1e-7a4d-4e59-9b0c-2d6f1a8e4c70"); background: url(//evil',
    'blob:https://music.example.org/3f2b8c1e-7a4d-4e59-9b0c-2d6f1a8e4c70 ', ' ' + BLOB,
    'blob:https://music.example.org/not-a-uuid', 'blob:https://music.example.org/', 'blob:',
    'blob:javascript:alert(1)//3f2b8c1e-7a4d-4e59-9b0c-2d6f1a8e4c70', 'https://music.example.org/3f2b8c1e-7a4d-4e59-9b0c-2d6f1a8e4c70',
    'data:image/png;base64,AAAA', '/theme-images/0123456789abcdef0123456789abcdef.jpg', '', null, undefined, 42, {}, [BLOB]
  ];
  for (const url of bad) assert.equal(ThemeApply.localPicture(url, true), null, String(url));
});

test('theLocalPictureIsAcceptedOnlyWhenItIsExactlyTheOneHandedIn', () => {
  const local = ThemeApply.localPicture(BLOB, false);
  assert.equal(ThemeApply.pictureUrl(BLOB), null);
  assert.equal(ThemeApply.pictureUrl(BLOB, local), BLOB);
  const other = 'blob:https://music.example.org/aaaaaaaa-7a4d-4e59-9b0c-2d6f1a8e4c70';
  assert.equal(ThemeApply.pictureUrl(other, local), null);
  assert.equal(ThemeApply.pictureUrl(BLOB, null), null);
  const evil = BLOB + '"); background: url(//evil';
  assert.equal(ThemeApply.pictureUrl(evil, { url: evil, animated: false }), null, 'a hand-made object is checked again');
  assert.equal(ThemeApply.pictureUrl(BLOB, BLOB), null, 'only what localPicture returned');
  // the secret links still work next to it
  assert.equal(ThemeApply.pictureUrl(PICTURE, local), PICTURE);
});

test('backdropShowsTheLocalPictureAndRespectsItsOwnMotionFlag', () => {
  const still = ThemeApply.localPicture(BLOB, false);
  const moving = ThemeApply.localPicture(BLOB, true);
  const back = ThemeApply.backdrop({ url: BLOB, dim: 30, blur: 2, fit: 'cover' }, still);
  assert.equal(back.url, BLOB);
  assert.equal(back.filter, 'blur(2px)');
  assert.equal(ThemeApply.backdrop({ url: BLOB, dim: 30, blur: 2, fit: 'cover' }), null, 'without it the page refuses the link');
  assert.equal(ThemeApply.canAnimate(BLOB), false);
  assert.equal(ThemeApply.canAnimate(BLOB, still), false);
  assert.equal(ThemeApply.canAnimate(BLOB, moving), true, 'a GIF chosen on this computer is frozen by Reduce motion too');
  assert.equal(ThemeApply.canAnimate('/theme-images/' + '0123456789abcdef'.repeat(2) + '.gif', still), true);
  assert.equal(ThemeApply.canAnimate('/theme-images/' + '0123456789abcdef'.repeat(2) + '.jpg', moving), false);
});

// ---- radius and font

test('scaleRadiusScalesPlainPixelLengths', () => {
  assert.equal(ThemeApply.scaleRadius('12px', 50), '6px');
  assert.equal(ThemeApply.scaleRadius(' 16px ', 200), '32px');
  assert.equal(ThemeApply.scaleRadius('5px', 70), '3.5px');
  assert.equal(ThemeApply.scaleRadius('999px', 200), '1998px');
  assert.equal(ThemeApply.scaleRadius('7px', 0), '0px');
  assert.equal(ThemeApply.scaleRadius('10px', 33), '3.3px');
  assert.equal(ThemeApply.scaleRadius('1px', 33.333), '0.33px');
});

test('scaleRadiusLeavesWhatItCannotScaleAlone', () => {
  for (const value of ['', '0', '0px', 'calc(1px + 2px)', '12em', '50%', 'inherit', '-4px', '1px 2px', null, undefined, 12]) {
    assert.equal(ThemeApply.scaleRadius(value, 50), null, String(value));
  }
  assert.equal(ThemeApply.scaleRadius('12px', NaN), null);
  assert.equal(ThemeApply.scaleRadius('12px', 'big'), null);
  assert.equal(ThemeApply.scaleRadius('10px', 900), '20px', 'the scale is held to 0..200');
  assert.equal(ThemeApply.scaleRadius('10px', -50), '0px');
});

test('fontStackIsNullForUnknownFonts', () => {
  assert.equal(ThemeApply.fontStack('Comic Sans'), null);
  assert.equal(ThemeApply.fontStack('constructor'), null);
  assert.equal(ThemeApply.fontStack('__proto__'), null);
  assert.equal(ThemeApply.fontStack(null), null);
  assert.equal(ThemeApply.fontStack('IBM Plex Mono'), "'IBM Plex Mono', ui-monospace, 'Cascadia Code', 'Consolas', monospace");
});

// ---- names

test('displayNameKeepsOrdinaryNamesAsTheyAre', () => {
  assert.equal(ThemeApply.displayName('Midnight Drive'), 'Midnight Drive');
  assert.equal(ThemeApply.displayName('  padded  '), 'padded');
  assert.equal(ThemeApply.displayName('<b>x</b>'), '<b>x</b>');
  assert.equal(ThemeApply.displayName('say "hi" & \'bye\''), 'say "hi" & \'bye\'');
  assert.equal(ThemeApply.displayName('夜のドライブ'), '夜のドライブ');
  assert.equal(ThemeApply.displayName('🌙 night'), '🌙 night');
});

test('displayNameDropsBidiControlsSoTheyCannotReorderTheText', () => {
  assert.equal(ThemeApply.displayName('\u202Eevil'), 'evil');
  assert.equal(ThemeApply.displayName('a\u202Ab\u202Bc\u202Dd\u202Ce\u202Cf'), 'abcdef');
  assert.equal(ThemeApply.displayName('a\u2066b\u2067c\u2068d\u2069e\u200Ef\u200Fg\u061Ch'), 'abcdefgh');
  // a stray close would end our own isolate early, so it must not survive
  assert.equal(ThemeApply.displayName('x' + PDI + '\u202Eevil'), 'xevil');
});

test('aNameWithNothingToSeeIsUntitled', () => {
  for (const name of ['', '   ', '\t\n', '\u200B\u200B', '\u00A0', '\u2800', '\u3164', '\uFEFF', '\u202E', '\u200D\u2060',
    '\uFE0F', '\u{E0041}\u{E0042}', '\u00AD', null, undefined, 7, {}]) {
    assert.equal(ThemeApply.displayName(name), 'Untitled theme', JSON.stringify(name));
  }
});

test('personNameFallsBackToSomeone', () => {
  assert.equal(ThemeApply.personName('Bob'), 'Bob');
  assert.equal(ThemeApply.personName('  Bob  '), 'Bob');
  assert.equal(ThemeApply.personName('\u202Eevil'), 'evil');
  for (const name of ['', '   ', '\u200B', null, undefined, 4]) assert.equal(ThemeApply.personName(name), 'someone', String(name));
});

test('isolateWrapsTextInIsolates', () => {
  assert.equal(ThemeApply.isolate('abc'), FSI + 'abc' + PDI);
});

test('communityEntriesNameTheirCreatorEachIsolated', () => {
  const label = ThemeApply.communityLabel(theme({ name: '\u202Eevil', owner: { id: '2', name: 'Bob' } }));
  assert.equal(label, FSI + 'evil' + PDI + ' \u2014 by ' + FSI + 'Bob' + PDI);
  assert.equal(ThemeApply.communityLabel(theme({ owner: { id: '2', name: '  ' } })),
    FSI + 'Midnight Drive' + PDI + ' \u2014 by ' + FSI + 'someone' + PDI);
  assert.equal(ThemeApply.communityLabel(theme({ name: '\u200B', owner: null })),
    FSI + 'Untitled theme' + PDI + ' \u2014 by ' + FSI + 'someone' + PDI);
});

test('myThemesMarkPrivateOnesAndOnesTheBotOwnerHid', () => {
  assert.equal(ThemeApply.mineLabel(theme({ visibility: 'public' })), FSI + 'Midnight Drive' + PDI);
  assert.equal(ThemeApply.mineLabel(theme({ visibility: 'private' })), FSI + 'Midnight Drive' + PDI + ' (private)');
  assert.equal(ThemeApply.mineLabel(theme({ visibility: 'private', hiddenByOwner: true })),
    FSI + 'Midnight Drive' + PDI + ' (hidden by the bot owner)');
});

// ---- which themes are used at all

test('aThemeIsUsableOnlyWithValidIdBaseAndColors', () => {
  assert.equal(ThemeApply.usable(theme()), true);
  assert.equal(ThemeApply.usable(theme({ base: 'nebula' })), true);
  assert.equal(ThemeApply.usable(theme({ colors: Object.assign({}, COLORS, { glass: '#10131c' }) })), true);
  for (const bad of [
    null, undefined, 'x', [], theme({ id: 'short' }), theme({ id: 'K3J9X0Q2M7AB' }), theme({ id: 'k3j9x0q2m7a;' }),
    theme({ id: 5 }), theme({ base: 'neon; color: red' }), theme({ base: 'nope' }), theme({ base: 'constructor' }),
    theme({ name: 5 }), theme({ colors: null }), theme({ colors: [] })
  ]) {
    assert.equal(ThemeApply.usable(bad), false, JSON.stringify(bad));
  }
  for (const slot of ThemePalette.SLOTS) {
    const missing = Object.assign({}, COLORS);
    delete missing[slot];
    assert.equal(ThemeApply.usable(theme({ colors: missing })), false, 'missing ' + slot);
    for (const value of ['red', '#12345', '#1234567', '#12345g', 'url(//evil.example/x.gif)', '#112233; color: red', '#112233\n', '', 5, null]) {
      assert.equal(ThemeApply.usable(theme({ colors: Object.assign({}, COLORS, { [slot]: value }) })), false, slot + '=' + value);
    }
  }
});

test('groupThemesSplitsCommunityFromMineAndSortsByName', () => {
  const list = [
    theme({ id: 'aaaaaaaaaaa1', name: 'zebra', mine: true }),
    theme({ id: 'aaaaaaaaaaa2', name: 'Alpha', mine: false }),
    theme({ id: 'aaaaaaaaaaa3', name: 'beta', mine: false }),
    theme({ id: 'aaaaaaaaaaa4', name: 'apple', mine: true, visibility: 'private' }),
    theme({ id: 'aaaaaaaaaaa5', name: 'private of someone else', mine: false, visibility: 'private' }),
    theme({ id: 'bad', name: 'broken', mine: true }),
    theme({ id: 'aaaaaaaaaaa6', name: '\u200B', mine: false })
  ];
  const groups = ThemeApply.groupThemes(list);
  assert.deepEqual(groups.mine.map((t) => t.id), ['aaaaaaaaaaa4', 'aaaaaaaaaaa1']);
  assert.deepEqual(groups.community.map((t) => t.id), ['aaaaaaaaaaa2', 'aaaaaaaaaaa3', 'aaaaaaaaaaa6']);
  assert.deepEqual(ThemeApply.groupThemes(null), { community: [], mine: [] });
});

// ---- the choice and its fallbacks

test('customChoiceIdParsesOnlyTheRightShape', () => {
  assert.equal(ThemeApply.customId('custom:k3j9x0q2m7ab'), 'k3j9x0q2m7ab');
  for (const bad of ['custom:', 'custom:K3J9X0Q2M7AB', 'custom:k3j9x0q2m7a', 'custom:k3j9x0q2m7abc', 'custom:../etc/passwd',
    'ember', 'xcustom:k3j9x0q2m7ab', null, undefined, 3, '__proto__']) {
    assert.equal(ThemeApply.customId(bad), null, String(bad));
  }
});

test('resolveChoiceKeepsBuiltInsAndTheOldNihonName', () => {
  assert.deepEqual(ThemeApply.resolveChoice('ember', [], ''), { kind: 'builtin', id: 'ember' });
  assert.deepEqual(ThemeApply.resolveChoice('nihon', [], ''), { kind: 'builtin', id: 'sumi' });
  assert.deepEqual(ThemeApply.resolveChoice('nebula', null, ''), { kind: 'builtin', id: 'nebula' });
  for (const junk of [null, undefined, '', 'purple', 'constructor', '__proto__', 'custom:', 7]) {
    assert.deepEqual(ThemeApply.resolveChoice(junk, [theme()], 'ember'), { kind: 'builtin', id: 'nebula' }, String(junk));
  }
});

test('resolveChoiceFindsACustomTheme', () => {
  const t = theme();
  const r = ThemeApply.resolveChoice('custom:k3j9x0q2m7ab', [theme({ id: 'aaaaaaaaaaa1' }), t], 'steel');
  assert.deepEqual(r, { kind: 'custom', theme: t });
});

test('aMissingCustomThemeFallsBackToItsBaseThenToNebula', () => {
  const r = ThemeApply.resolveChoice('custom:k3j9x0q2m7ab', [theme({ id: 'aaaaaaaaaaa1' })], 'steel');
  assert.deepEqual(r, { kind: 'builtin', id: 'steel', fellBack: true });
  assert.deepEqual(ThemeApply.resolveChoice('custom:k3j9x0q2m7ab', [], 'not-a-theme'), { kind: 'builtin', id: 'nebula', fellBack: true });
  assert.deepEqual(ThemeApply.resolveChoice('custom:k3j9x0q2m7ab', [], ''), { kind: 'builtin', id: 'nebula', fellBack: true });
  assert.deepEqual(ThemeApply.resolveChoice('custom:k3j9x0q2m7ab', [], null), { kind: 'builtin', id: 'nebula', fellBack: true });
  // a theme with broken colors is as good as missing
  const broken = theme({ colors: Object.assign({}, COLORS, { accent: 'red' }) });
  assert.deepEqual(ThemeApply.resolveChoice('custom:k3j9x0q2m7ab', [broken], 'dawn'), { kind: 'builtin', id: 'dawn', fellBack: true });
});

test('beforeTheListHasLoadedACustomChoiceWaitsOnItsBase', () => {
  assert.deepEqual(ThemeApply.resolveChoice('custom:k3j9x0q2m7ab', null, 'ember'), { kind: 'builtin', id: 'ember', pending: true });
  assert.deepEqual(ThemeApply.resolveChoice('custom:k3j9x0q2m7ab', null, ''), { kind: 'builtin', id: 'nebula', pending: true });
});

// ---- decoration pieces switched off (data-deco-off)

const PIECES = JSON.parse(fs.readFileSync(path.join(__dirname, '../../main/resources/web/theme-pieces.json'), 'utf8'));
const Colors = require('./css-colors.js');

function idsOf(base, onlyBackground) {
  return PIECES[base].pieces.filter((p) => !onlyBackground || p.background).map((p) => p.id);
}

function deepFreeze(value) {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(deepFreeze);
    Object.freeze(value);
  }
  return value;
}

test('decoOffForIsTheSavedListOfTheStartingThemesPieces', () => {
  assert.equal(ThemeApply.decoOffFor(theme({ base: 'revival', decorationsOff: ['rays', 'record'] }), false, PIECES), 'rays record');
  assert.equal(ThemeApply.decoOffFor(theme({ base: 'ember', decorationsOff: [] }), false, PIECES), '');
  // what is not a piece of the starting theme, or not an id at all, never reaches the page
  assert.equal(ThemeApply.decoOffFor(theme({ base: 'ember', decorationsOff: ['rays', 'fire', 'fire', 'a b', '"x"', 5, null, 'edge'] }),
    false, PIECES), 'fire edge');
  assert.equal(ThemeApply.decoOffFor(theme({ base: 'ember', decorationsOff: 'flames' }), false, PIECES), '');
  for (const junk of [null, undefined, 'x', 7]) assert.equal(ThemeApply.decoOffFor(junk, true, PIECES), '');
});

test('aPictureTurnsBackgroundPiecesOff', () => {
  for (const base of ThemeApply.BUILT_IN) {
    assert.equal(ThemeApply.decoOffFor(theme({ base: base, decorationsOff: [] }), true, PIECES), idsOf(base, true).join(' '), base);
    assert.equal(ThemeApply.decoOffFor(theme({ base: base, decorationsOff: [] }), false, PIECES), '', base);
  }
  // the saved ones first, then the background ones, each once; the others stay on
  assert.equal(ThemeApply.decoOffFor(theme({ base: 'revival', decorationsOff: ['record', 'rays'] }), true, PIECES), 'record rays sunrise');
  assert.equal(ThemeApply.decoOffFor(theme({ base: 'sumi', decorationsOff: ['seal'] }), true, PIECES), 'seal mist kanji');
  assert.equal(ThemeApply.decoOffFor(theme({ base: 'ember', decorationsOff: [] }), true, PIECES), '', 'Ember paints no background');
  // only a real yes counts
  assert.equal(ThemeApply.decoOffFor(theme({ base: 'grove', decorationsOff: [] }), 'yes', PIECES), '');
});

test('aThemeInTheOldShapeGetsTheServersConversion', () => {
  // the creator's preview still says "effects" until it has piece switches: off is every piece off, as the server reads it
  const old = theme({ base: 'cipher', effects: false });
  delete old.decorationsOff;
  assert.equal(ThemeApply.decoOffFor(old, false, PIECES), idsOf('cipher').join(' '));
  assert.equal(ThemeApply.decoOffFor(Object.assign({}, old, { effects: true }), true, PIECES), 'grid');
  const neither = Object.assign({}, old);
  delete neither.effects;
  assert.equal(ThemeApply.decoOffFor(neither, false, PIECES), '');
  // a list wins over the flag
  assert.equal(ThemeApply.decoOffFor(Object.assign({}, old, { decorationsOff: ['grid'] }), false, PIECES), 'grid');
});

test('withoutThePiecesListOnlyTheSavedIdsThatLookLikeIdsGoThrough', () => {
  // the list didn't load: the saved list as the server checked it, nothing added for a picture
  for (const pieces of [null, undefined, {}, { revival: null }, { revival: { pieces: 'x' } }, 'x']) {
    assert.equal(ThemeApply.decoOffFor(theme({ base: 'revival', decorationsOff: ['rays', 'rays', 'a b', 'X', 'dawn-dark'] }), true, pieces),
      'rays dawn-dark', JSON.stringify(pieces));
  }
  // and a broken entry in the list is skipped
  const odd = { ember: { decoExtra: '#fde047', pieces: [{ id: 'flames', background: true }, { id: 'a b', background: true }, null] } };
  assert.equal(ThemeApply.decoOffFor(theme({ base: 'ember', decorationsOff: [] }), true, odd), 'flames');
  // a theme of another base finds nothing of its own
  assert.equal(ThemeApply.decoOffFor(theme({ base: '__proto__', decorationsOff: ['glow'] }), true, PIECES), '');
});

test('decoOffForIsPure', () => {
  const t = deepFreeze(theme({ base: 'revival', decorationsOff: ['record', 'rays'] }));
  const pieces = deepFreeze(JSON.parse(JSON.stringify(PIECES)));
  const first = ThemeApply.decoOffFor(t, true, pieces);
  assert.equal(ThemeApply.decoOffFor(t, true, pieces), first);
  assert.deepEqual(t.decorationsOff, ['record', 'rays']);
  assert.deepEqual(pieces, PIECES);
  assert.equal(typeof first, 'string');
  // nothing in the module reaches for the page
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/theme-apply.js'), 'utf8');
  assert.doesNotMatch(source, /\b(document|localStorage|fetch|XMLHttpRequest)\b/);
});

test('theExtraDecorationColorIsCheckedWhenItIsThere', () => {
  assert.equal(ThemeApply.usable(theme()), true, 'a theme from an older server has none');
  assert.equal(ThemeApply.usable(theme({ colors: Object.assign({}, COLORS, { decoExtra: '#dc2626' }) })), true);
  for (const value of ['red', '#12345', 'url(//evil.example/x.gif)', '#112233; color: red', '', 5, null]) {
    assert.equal(ThemeApply.usable(theme({ colors: Object.assign({}, COLORS, { decoExtra: value }) })), false, String(value));
  }
});

test('aPictureHidesTheAmbientGlowOnEveryBase', () => {
  // The panel's shared drifting glow (body::before) is no piece; over a custom theme's picture it would paint above
  // #themeBackdrop, so a picture hides it. Steel, Sumi and Dawn Dark bring it back as their wash / mist piece, which is
  // a background piece (off with a picture); the rule wins even if the pieces list never loaded.
  const css = Colors.panelCss();
  const decls = Colors.declarations(css).filter((d) => d.rule.at.length === 0 && /(^|\s)body::before$/.test(d.selector) &&
    d.decl.prop === 'display');
  assert.ok(decls.length >= 4, 'display rules of body::before found: ' + decls.length);
  function shown(attrs) {
    const live = decls.filter((d) => {
      const head = Colors.splitFirst(d.spaced).head;
      return !/^(html|:root)/.test(head) || Colors.htmlMatches(d.spaced, attrs);
    });
    live.sort((a, b) => {
      const x = [a.decl.important ? 1 : 0].concat(a.spec, [a.order]), y = [b.decl.important ? 1 : 0].concat(b.spec, [b.order]);
      for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return x[i] - y[i];
      return 0;
    });
    return live.length ? live[live.length - 1].decl.value.trim() : 'block';
  }
  for (const base of ThemeApply.BUILT_IN) {
    const own = base === 'nebula' ? {} : { 'data-theme': base };
    const custom = Object.assign({ 'data-custom-theme': 'k3j9x0q2m7ab' }, own);
    const withPicture = Object.assign({ 'data-custom-picture': '' }, custom);
    const off = ThemeApply.decoOffFor(theme({ base: base, decorationsOff: [] }), true, PIECES);
    if (off) withPicture['data-deco-off'] = off;
    assert.equal(shown(withPicture), 'none', base + ' with a picture');
    const noList = Object.assign({ 'data-custom-picture': '' }, custom);
    assert.equal(shown(noList), 'none', base + ' with a picture, pieces list not loaded');
    assert.equal(shown(own), shown(custom), base + ': without a picture a custom theme shows what its base shows');
  }
  assert.notEqual(shown({}), 'none', 'Nebula has its glow');
  assert.notEqual(shown({ 'data-theme': 'steel' }), 'none', 'Steel has its wash');
  // the bases that bring the glow back do it under a background piece
  for (const [base, piece] of [['steel', 'wash'], ['sumi', 'mist'], ['dawn-dark', 'wash']]) {
    assert.ok(PIECES[base].pieces.some((p) => p.id === piece && p.background === true), base + ' ' + piece);
    assert.equal(shown({ 'data-theme': base, 'data-deco-off': piece }), 'none', base + ' without its ' + piece);
  }
});
