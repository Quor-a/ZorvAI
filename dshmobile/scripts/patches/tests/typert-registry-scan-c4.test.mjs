import fs from 'node:fs';import { test } from 'node:test';import { versionedFixture } from './lib/fixture.mjs';import { planTypertRegistryScan } from '../data/registry-scan-c4.mjs';import {join} from 'node:path';import {tmpdir} from 'node:os';import {pathToFileURL} from 'node:url';import assert from 'node:assert/strict';
test('typert scan preserves live async registration, removal, explicit failures and registration counts', async () => {
const pristine=fs.readFileSync(versionedFixture('dsh-typert-loader','lib','index.js'),'utf8');
assert.equal(planTypertRegistryScan(planTypertRegistryScan(pristine)),planTypertRegistryScan(pristine));
assert.throws(() => planTypertRegistryScan(pristine.replace('const qualifies = (entryName) => {','broken')),/anchor mismatch/);
const scratch=fs.mkdtempSync(join(tmpdir(),'c4-typert-'));
const schemas=join(scratch,'node_modules','@deepseek-ai','schemastery');fs.mkdirSync(schemas,{recursive:true});fs.writeFileSync(join(schemas,'package.json'),JSON.stringify({type:'module',exports:'./index.js'}));fs.writeFileSync(join(schemas,'index.js'),'const z={object:()=>({}),array:()=>({default:()=>({})}),string:()=>({min:()=>({})})};export default z;');
try{
 for(const [variant,source] of [['original',pristine],['candidate',planTypertRegistryScan(pristine)]]){
  const module=join(scratch,variant+'.mjs');fs.writeFileSync(module,source);const {apply}=await import(pathToFileURL(module));
  const app=join(scratch,variant);fs.mkdirSync(join(app,'node_modules'),{recursive:true});const baseUrl=pathToFileURL(join(app,'config.js')).href;const rows=[],hooks=new Map(),mounted=new Set();let scans=0;
  for(let n=0;n<24;n++){const name='typert-'+n,root=join(app,'node_modules',name);fs.mkdirSync(root);fs.writeFileSync(join(root,'package.json'),JSON.stringify({name,type:'module',exports:{'./package.json':'./package.json','./typert':'./typert.js'}}));fs.writeFileSync(join(root,'typert.js'),'export const TYPERT='+JSON.stringify({package:name,face:'host',schemas:[],model:{services:[],events:[],objects:[]},invocations:[]})+';');rows.push({options:{name},fiber:{},disabled:false});}
  const ctx={baseUrl,get(){},loader:{entries(){scans++;return rows;}},typert:{register(m){assert.ok(!mounted.has(m.package));mounted.add(m.package);return async()=>{mounted.delete(m.package);};}},effect(){},on(e,h){hooks.set(e,h)},logger:{error(error){throw error;}}};
  await apply(ctx,{packages:[]});assert.equal(mounted.size,24);console.log(JSON.stringify({variant,mounted:mounted.size,scans}));assert.equal(scans,variant==='candidate'?26:49); // seed + initial scan + async live qualifies
  rows[0].disabled=true;hooks.get('internal/plugin')({entry:rows[0]});await new Promise(r=>setTimeout(r,0));assert.equal(mounted.size,23);
  rows[0].disabled=false;hooks.get('internal/plugin')({entry:rows[0]});await new Promise(r=>setTimeout(r,0));assert.equal(mounted.size,24);
  // A package removed after async import started must never register.
  const n='typert-delayed',root=join(app,'node_modules',n);fs.mkdirSync(root);fs.writeFileSync(join(root,'package.json'),JSON.stringify({name:n,type:'module',exports:{'./package.json':'./package.json','./typert':'./typert.js'}}));fs.writeFileSync(join(root,'typert.js'),'await new Promise(r=>setTimeout(r,30));export const TYPERT='+JSON.stringify({package:n,face:'host',schemas:[],model:{services:[],events:[],objects:[]},invocations:[]})+';');const late={options:{name:n},fiber:{},disabled:false};rows.push(late);hooks.get('internal/plugin')({entry:late});await new Promise(r=>setTimeout(r,5));rows.splice(rows.indexOf(late),1);hooks.get('internal/plugin')({entry:late});await new Promise(r=>setTimeout(r,50));assert.ok(!mounted.has(n));
  // A synchronous malformed-export callback can remove a later dirty entry.
  // The temporary mounted-name index must not outlive that callback.
  const extraRows=[];
  for(const [extra,target] of [['bad-extra',16],['good-extra','./typert.js']]){
    const dir=join(app,'node_modules',extra);fs.mkdirSync(dir);
    fs.writeFileSync(join(dir,'package.json'),JSON.stringify({name:extra,type:'module',exports:{'./package.json':'./package.json','./typert':target}}));
    fs.writeFileSync(join(dir,'typert.js'),'export const TYPERT='+JSON.stringify({package:extra,face:'host',schemas:[],model:{services:[],events:[],objects:[]},invocations:[]})+';');
    const row={options:{name:extra},fiber:{},disabled:false};rows.push(row);extraRows.push(row);
  }
  let reported=0;ctx.logger.error=()=>{reported++;const index=rows.indexOf(extraRows[1]);if(index>=0)rows.splice(index,1);};
  for(const row of extraRows)hooks.get('internal/plugin')({entry:row});
  await new Promise(r=>setTimeout(r,30));assert.equal(reported,1);assert.ok(!mounted.has('good-extra'));
  rows.splice(rows.indexOf(extraRows[0]),1);
  // Explicit packages without typert still reject activation even if absent from rows.
  const broken=join(app,'node_modules','explicit-broken');fs.mkdirSync(broken);fs.writeFileSync(join(broken,'package.json'),JSON.stringify({name:'explicit-broken',exports:{'./package.json':'./package.json'}}));await assert.rejects(apply(ctx,{packages:['explicit-broken']}),/does not export/);
 }
 console.log('typert baseline/candidate registration equivalence, runtime remove/readd, async disappearance and activation failures PASS');
}finally{fs.rmSync(scratch,{recursive:true,force:true})}

});
