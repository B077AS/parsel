// Builds what a release ships, from the jars `mvn package` leaves in target/package. Used by the release workflow and by hand:
//
//   cd frontend && npm run build        the page, into src/main/resources/ui
//   mvn clean package -DskipTests       the jars, into target/package
//   node packaging/package.mjs <kind> [--version 1.2.3] [--out target/dist]
//
// kinds:  windows-installer   Parsel-<v>-windows-x64-setup.exe   (a branded wizard; needs Inno Setup 6: winget install JRSoftware.InnoSetup)
//         windows-portable    Parsel-<v>-windows-x64-portable.zip (unzip and run Parsel\Parsel.exe)
//         linux-deb           Parsel-<v>-linux-amd64.deb          (on Linux, needs fakeroot and dpkg; build it on the oldest Ubuntu you want it to
//                                                                  install on: the package names its dependencies as that system calls them)
//         linux-tarball       Parsel-<v>-linux-x64.tar.gz         (on Linux; unpack and run Parsel/bin/Parsel)
//         linux-appimage      Parsel-<v>-linux-x86_64.AppImage    (on Linux, needs appimagetool on the PATH; NOT the recommended Linux download: it still needs the system's
//                                                                  GTK, WebKitGTK and libsecret, so it gains little over the .deb and the .tar.gz)
//         jars                Parsel-<v>.jar                      (one jar with everything in it; any system with Java 25: java -jar Parsel-<v>.jar)
//
// Every installer carries its own Java runtime (made with jlink from the modules listed below), so users need no Java.
// Needs JDK 25 on the PATH (jpackage, jlink) and Node 20+.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageDir = path.join(root, 'target', 'package');

// What the app needs at run time. `jdeps` finds the first five; the rest cannot be seen from the code: TLS elliptic curves
// (jdk.crypto.ec), the less common mail character sets (jdk.charsets), logging, JNDI/DNS and XML used by the mail and HTML libraries.
const MODULES = [
  'java.base', 'java.desktop', 'java.net.http', 'java.sql', 'jdk.httpserver',
  'java.logging', 'java.naming', 'java.xml', 'java.management', 'java.security.sasl', 'java.security.jgss',
  'jdk.unsupported', 'jdk.crypto.ec', 'jdk.charsets', 'jdk.naming.dns',
].join(',');

// Packages the .deb needs on the user's machine (the web view and the OS keystore are the system's, not bundled).
const DEB_DEPENDS = 'libgtk-3-0, libwebkit2gtk-4.1-0, libsecret-1-0';

function fail(message) {
  console.error('packaging: ' + message);
  process.exit(1);
}

function run(command, args, options = {}) {
  console.log('> ' + [command, ...args].join(' '));
  execFileSync(command, args, { stdio: 'inherit', ...options });
}

function parseArgs(argv) {
  const [kind, ...rest] = argv;
  const options = { kind, version: null, out: path.join(root, 'target', 'dist') };
  for (let i = 0; i < rest.length; i += 2) {
    if (rest[i] === '--version') options.version = rest[i + 1];
    else if (rest[i] === '--out') options.out = path.resolve(rest[i + 1]);
    else fail('unknown option ' + rest[i]);
  }
  return options;
}

function pomVersion() {
  const pom = fs.readFileSync(path.join(root, 'pom.xml'), 'utf8');
  const match = pom.match(/<artifactId>parsel<\/artifactId>\s*<version>([^<]+)<\/version>/);
  if (!match) fail('could not read the version from pom.xml');
  return match[1];
}

const options = parseArgs(process.argv.slice(2));
if (!options.kind) fail('say what to build: windows-installer, windows-portable, linux-deb, linux-tarball, linux-appimage or jars');
const version = (options.version ?? pomVersion()).replace(/^v/, '');
// jpackage wants 1, 1.2 or 1.2.3 (digits only); a tag like 1.2.0-beta1 keeps its full name on the files and 1.2.0 inside.
const appVersion = (version.match(/^\d+(\.\d+){0,2}/) ?? [])[0];
if (!appVersion) fail(`"${version}" does not start with a version number such as 1.2.3`);

const jar = fs.readdirSync(packageDir).find((f) => /^parsel-.*\.jar$/.test(f));
if (!jar) fail('target/package has no parsel jar: run `mvn clean package -DskipTests` first');
fs.mkdirSync(options.out, { recursive: true });

// A fresh scratch folder for jpackage's own output, so a half-finished earlier run can never leak into this one.
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'parsel-package-'));

function findInnoSetup() {
  const candidates = [
    process.env.ISCC_PATH,
    path.join(process.env.LOCALAPPDATA ?? '', 'Programs', 'Inno Setup 6', 'ISCC.exe'), // a per-user install, which winget makes
    'C:/Program Files (x86)/Inno Setup 6/ISCC.exe',
    'C:/Program Files/Inno Setup 6/ISCC.exe',
  ];
  const found = candidates.find((c) => c && fs.existsSync(c));
  if (!found) fail('Inno Setup 6 is not installed: run  winget install JRSoftware.InnoSetup  (or set ISCC_PATH to ISCC.exe)');
  return found;
}

function jpackage(extra) {
  const args = [
    '--input', packageDir,
    '--main-jar', jar,
    '--main-class', 'io.github.b077as.parsel.App',
    '--name', 'Parsel',
    '--app-version', appVersion,
    '--vendor', 'Parsel',
    '--description', 'Parsel',
    '--java-options', '--enable-native-access=ALL-UNNAMED',
    '--add-modules', MODULES,
    '--dest', scratch,
    // The Apache licence asks that a copy travels with the program: it ends up next to the app in every package.
    ...(fs.existsSync(path.join(root, 'LICENSE')) ? ['--app-content', path.join(root, 'LICENSE')] : []),
    ...extra,
  ];
  run('jpackage', args);
}

function zip(folder, name, target) {
  const file = path.join(options.out, target);
  fs.rmSync(file, { force: true });
  if (process.platform === 'win32') {
    // bsdtar ships with Windows 10 and later and writes zip when asked by the extension. By full path: Git's own tar, which is
    // often first on the PATH, cannot write zip files and trips over drive letters.
    run(path.join(process.env.SystemRoot ?? 'C:/Windows', 'System32', 'tar.exe'), ['-a', '-c', '-f', file, '-C', folder, name]);
  } else {
    run('zip', ['-q', '-r', file, name], { cwd: folder });
  }
  return file;
}

function finish(from, target) {
  const file = path.join(options.out, target);
  // jpackage's output is read-only, which would make the next run unable to replace it.
  if (fs.existsSync(file)) fs.chmodSync(file, 0o666);
  fs.rmSync(file, { force: true });
  fs.copyFileSync(from, file);
  fs.chmodSync(file, 0o644);
  console.log(`\nbuilt ${file} (${(fs.statSync(file).size / 1048576).toFixed(1)} MB)`);
}

switch (options.kind) {
  case 'windows-installer': {
    if (process.platform !== 'win32') fail('the Windows installer is built on Windows');
    const icon = path.join(root, 'src', 'main', 'resources', 'icon.ico');
    // Two steps: jpackage makes the app folder (Parsel.exe, the jars and a private Java runtime), then Inno Setup wraps it in the wizard.
    jpackage(['--type', 'app-image', '--icon', icon]);
    const outName = `Parsel-${version}-windows-x64-setup`;
    const setup = path.join(options.out, outName + '.exe');
    if (fs.existsSync(setup)) fs.chmodSync(setup, 0o666);
    fs.rmSync(setup, { force: true });
    const fileVersion = [...appVersion.split('.'), '0', '0', '0'].slice(0, 4).join('.');
    run(findInnoSetup(), [
      '/Qp', // quiet, but still print problems
      `/DAppVersion=${version}`,
      `/DFileVersion=${fileVersion}`,
      `/DAppImageDir=${path.join(scratch, 'Parsel')}`,
      `/DOutputDir=${options.out}`,
      `/DOutputName=${outName}`,
      `/DIconFile=${icon}`,
      path.join(root, 'packaging', 'windows', 'parsel.iss'),
    ]);
    console.log(`\nbuilt ${setup} (${(fs.statSync(setup).size / 1048576).toFixed(1)} MB)`);
    break;
  }
  case 'windows-portable': {
    if (process.platform !== 'win32') fail('the Windows build is made on Windows');
    jpackage(['--type', 'app-image', '--icon', path.join(root, 'src', 'main', 'resources', 'icon.ico')]);
    console.log('\nbuilt ' + zip(scratch, 'Parsel', `Parsel-${version}-windows-x64-portable.zip`));
    break;
  }
  case 'linux-deb': {
    if (process.platform !== 'linux') fail('the .deb is built on Linux');
    jpackage([
      '--type', 'deb',
      '--icon', path.join(root, 'packaging', 'linux', 'icons', '256.png'),
      '--resource-dir', path.join(root, 'packaging', 'linux', 'jpackage'),
      '--linux-package-name', 'parsel',
      '--linux-app-category', 'mail',
      '--linux-menu-group', 'Network;Email;',
      '--linux-shortcut',
      '--linux-package-deps', DEB_DEPENDS,
      // Whoever is named here is shown as the package's maintainer (the release workflow passes the repository owner's GitHub no-reply address).
      ...(process.env.PARSEL_DEB_MAINTAINER ? ['--linux-deb-maintainer', process.env.PARSEL_DEB_MAINTAINER] : []),
    ]);
    const deb = fs.readdirSync(scratch).find((f) => f.endsWith('.deb'));
    if (!deb) fail('jpackage produced no .deb');
    finish(path.join(scratch, deb), `Parsel-${version}-linux-amd64.deb`);
    break;
  }
  case 'linux-tarball': {
    if (process.platform !== 'linux') fail('the tarball is built on Linux');
    jpackage(['--type', 'app-image', '--icon', path.join(root, 'packaging', 'linux', 'icons', '256.png')]);
    const file = path.join(options.out, `Parsel-${version}-linux-x64.tar.gz`);
    fs.rmSync(file, { force: true });
    run('tar', ['-czf', file, '-C', scratch, 'Parsel']);
    console.log(`\nbuilt ${file} (${(fs.statSync(file).size / 1048576).toFixed(1)} MB)`);
    break;
  }
  case 'linux-appimage': {
    if (process.platform !== 'linux') fail('the AppImage is built on Linux');
    // The same app folder as the tarball, wrapped in an AppDir: AppRun starts it, and the desktop file and icon sit at the top.
    jpackage(['--type', 'app-image', '--icon', path.join(root, 'packaging', 'linux', 'icons', '256.png')]);
    const appDir = path.join(scratch, 'Parsel.AppDir');
    fs.mkdirSync(appDir);
    fs.renameSync(path.join(scratch, 'Parsel'), path.join(appDir, 'Parsel'));
    fs.writeFileSync(path.join(appDir, 'AppRun'), '#!/bin/sh\nHERE="$(dirname "$(readlink -f "$0")")"\nexec "$HERE/Parsel/bin/Parsel" "$@"\n', { mode: 0o755 });
    fs.writeFileSync(path.join(appDir, 'com.parsel.app.desktop'),
      fs.readFileSync(path.join(root, 'packaging', 'linux', 'com.parsel.app.desktop'), 'utf8').replace('Exec=parsel', 'Exec=AppRun'));
    fs.copyFileSync(path.join(root, 'packaging', 'linux', 'icons', '256.png'), path.join(appDir, 'com.parsel.app.png'));
    const file = path.join(options.out, `Parsel-${version}-linux-x86_64.AppImage`);
    fs.rmSync(file, { force: true });
    run('appimagetool', [appDir, file], { env: { ...process.env, ARCH: 'x86_64' } });
    console.log(`\nbuilt ${file} (${(fs.statSync(file).size / 1048576).toFixed(1)} MB)`);
    break;
  }
  case 'jars': {
    // One jar with every dependency inside (maven-shade): `java -jar Parsel-<v>.jar` is all it takes.
    const fat = path.join(root, 'target', jar.replace(/\.jar$/, '-all.jar'));
    if (!fs.existsSync(fat)) fail(`no ${fat}: run mvn clean package -DskipTests first`);
    finish(fat, `Parsel-${version}.jar`);
    break;
  }
  default:
    fail(`unknown kind "${options.kind}"`);
}

fs.rmSync(scratch, { recursive: true, force: true });
