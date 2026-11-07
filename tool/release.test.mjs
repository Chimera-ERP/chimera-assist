import {test} from 'node:test';
import assert from 'node:assert/strict';
import {version,validateFlutterVersion,validateApk,releaseManifest,signingCertificate} from './release.mjs';
const v={name:'0.1.1',code:2,tag:'v0.1.1'};
const badge="package: name='ro.chimeraerp.assist' versionCode='2' versionName='0.1.1'";
const cert=`Signer #1 certificate SHA-256 digest: ${signingCertificate}`;
test('release version is read unambiguously from the Android build',()=>{
 assert.deepEqual(version('    versionName = "0.1.1"\n    versionCode = 2\n'),v);
 for(const source of ['versionName = "0.1.1-beta"\nversionCode = 2','versionName = "0.1.1"\nversionCode = 0','versionName = "0.1.1"\nversionCode = 2\nversionCode = 3'])assert.throws(()=>version(source));
});
test('Flutter and Android cannot publish inconsistent versions',()=>{
 validateFlutterVersion(v,'version: 0.1.1+2\n');
 for(const source of ['version: 0.1.2+2','version: 0.1.1+3','version: 0.1.1+2\nversion: 0.1.1+2'])assert.throws(()=>validateFlutterVersion(v,source));
});
test('package verification rejects another app, debug, wrong version or another signer',()=>{
 validateApk(v,badge,cert);
 for(const bad of [badge.replace('assist','companion'),badge.replace("versionCode='2'","versionCode='1'"),badge.replace("versionName='0.1.1'","versionName='0.1.0'"),badge+'\napplication-debuggable'])assert.throws(()=>validateApk(v,bad,cert));
 assert.throws(()=>validateApk(v,badge,cert.replace(signingCertificate,'a'.repeat(64))));
 assert.throws(()=>validateApk(v,badge,cert+'\n'+cert.replace('#1','#2')));
});
test('public download metadata binds exact APK bytes, signer, source and versioned asset',()=>{
 const manifest=releaseManifest(v,Buffer.from('synthetic APK'),'a'.repeat(40));
 assert.equal(manifest.packageName,'ro.chimeraerp.assist');assert.equal(manifest.size,13);
 assert.equal(manifest.signingCertificateSha256,signingCertificate);
 assert.equal(manifest.url,'https://github.com/Chimera-ERP/chimera-assist/releases/download/v0.1.1/chimera-assist-0.1.1.apk');
 assert.equal(manifest.sha256.length,64);assert.notEqual(manifest.sha256,releaseManifest(v,Buffer.from('changed'),'a'.repeat(40)).sha256);
 assert.throws(()=>releaseManifest(v,Buffer.from('x'),'main'));
});
