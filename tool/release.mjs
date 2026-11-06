import {createHash} from 'node:crypto';
import {readFileSync,writeFileSync,copyFileSync,mkdirSync,appendFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';

export const signingCertificate = '23ff3ea173fdb9ca050af2099b922bacd2ddbb71a4194d4d875bf2ccdf79aad4';
export const repository = 'Chimera-ERP/chimera-assist';
export function version(source) {
  const names=[...source.matchAll(/^\s*versionName = "(\d+\.\d+\.\d+)"\s*$/gm)];
  const codes=[...source.matchAll(/^\s*versionCode = ([1-9]\d*)\s*$/gm)];
  if(names.length!==1||codes.length!==1||!Number.isSafeInteger(Number(codes[0][1])))throw Error('Expected one stable Android versionName/versionCode');
  return {name:names[0][1],code:Number(codes[0][1]),tag:`v${names[0][1]}`};
}
export function validateApk(v,badging,certificate) {
  const pkg = /^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m.exec(badging);
  const certs = [...certificate.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)$/gm)];
  if (!pkg || pkg[1] !== 'ro.chimeraerp.assist' || Number(pkg[2]) !== v.code || pkg[3] !== v.name ||
    /^application-debuggable/m.test(badging) || certs.length !== 1 || certs[0][1] !== signingCertificate) {
    throw Error('APK package, version, release mode or signing identity mismatch');
  }
}
export function releaseManifest(v,bytes,commit) {
  if(!/^[a-f0-9]{40}$/.test(commit)) throw Error('Expected exact source commit');
  return {version:1, packageName:'ro.chimeraerp.assist', versionName:v.name,versionCode:v.code,
    size:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex'),signingCertificateSha256:signingCertificate,
    url:`https://github.com/${repository}/releases/download/${v.tag}/chimera-assist-${v.name}.apk`,sourceCommit:commit};
}
const run=(cmd,args)=>execFileSync(cmd,args,{encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
function plan(v) {
  const releases=JSON.parse(run('gh',['api',`repos/${repository}/releases?per_page=100`]));
  const published=releases.find(r=>r.tag_name===v.tag);
  if(published){
    if(published.draft)throw Error('A draft release needs reconciliation before retry');
    return false;
  }
  const stable=releases.filter(r=>!r.prerelease&&!r.draft&&/^v\d+\.\d+\.\d+$/.test(r.tag_name));
  for(const r of stable){
    const compare=r.tag_name.slice(1).split('.').map(Number), next=v.name.split('.').map(Number);
    let delta=0;for(let i=0;i<3&&!delta;i++)delta=next[i]-compare[i];
    if(delta<=0)throw Error('Release name must increase');
  }
  const latest=stable.find(r=>r.assets.some(a=>a.name==='assist-update.json'));
  let installedFloor=0;
  if(latest){
    const asset=latest.assets.find(a=>a.name==='assist-update.json');
    const previous=JSON.parse(run('gh',['api',`repos/${repository}/releases/assets/${asset.id}`,'-H','Accept: application/octet-stream']));
    if(!Number.isSafeInteger(previous.versionCode))throw Error('Previous release has no valid build number');
    installedFloor=previous.versionCode;
  }
  if(v.code<=installedFloor)throw Error('Android versionCode must increase beyond the installed release');
  return true;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const v=version(readFileSync('app/build.gradle.kts','utf8'));
  const mode=process.argv[2];
  if(mode==='plan'){
    const publish=plan(v);
    appendFileSync(process.env.GITHUB_OUTPUT,`publish=${publish}\ntag=${v.tag}\nversion=${v.name}\n`);
  }else if(mode==='package'){
    const apk=process.argv[3];
    validateApk(v,run(process.env.AAPT,['dump','badging',apk]),run(process.env.APKSIGNER,['verify','--verbose','--print-certs',apk]));
    const manifest=releaseManifest(v,readFileSync(apk),process.env.GITHUB_SHA||run('git',['rev-parse','HEAD']));
    mkdirSync('release',{recursive:true});
    copyFileSync(apk,`release/chimera-assist-${v.name}.apk`);
    writeFileSync('release/assist-update.json',JSON.stringify(manifest,null,2)+'\n');
    writeFileSync('release/SHA256SUMS',`${manifest.sha256}  chimera-assist-${v.name}.apk\n`);
    writeFileSync('release/notes.md',`Chimera Assist ${v.name} (Android build ${v.code}).\n\nBuilt by GitHub Actions from ${manifest.sourceCommit}.\n\nAndroid 10+ test release. Sign in through auth.chimeraerp.ro, grant caller identification and overlay permissions, then activate the assistant. No physical-device acceptance is claimed. Earlier locally signed debug builds must be uninstalled once before this first release-signed APK; future releases keep this signing identity. Business data stays in ERP.\n\nAPK SHA256: \`${manifest.sha256}\`\n`);
    console.log(JSON.stringify(manifest));
  }else throw Error('Expected plan or package');
}
