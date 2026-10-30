const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { hueFromPixels, palette } = require('./ui/appearance.js');
assert.equal(hueFromPixels([0,0,0,255,255,255,255,255]),26);
assert.equal(hueFromPixels([255,0,0,255]),0);
assert.equal(hueFromPixels([0,255,0,255]),120);
assert.equal(hueFromPixels([0,0,255,255]),240);
assert.equal(hueFromPixels([0,255,0,0]),26);
function rgb(hsl) {
  const [h,s,l] = hsl.match(/[\d.]+/g).map(Number), a = s / 100 * Math.min(l / 100, 1-l/100);
  return [0,8,4].map(n => { const k = (n + h/30)%12; return l/100-a*Math.max(-1,Math.min(k-3,9-k,1)); });
}
function luminance(value) { return rgb(value).map(c => c<=.04045 ? c/12.92 : ((c+.055)/1.055)**2.4).reduce((sum,c,i)=>sum+c*[.2126,.7152,.0722][i],0); }
for (let hue=0;hue<360;hue++) for (const dark of [false,true]) {
  const p=palette(hue,dark);
  for (const fg of ['fg','muted','accent']) for (const bg of ['bg','surface']) {
    const a=luminance(p[fg]),b=luminance(p[bg]),contrast=(Math.max(a,b)+.05)/(Math.min(a,b)+.05);
    assert(contrast>=4.5, `contrast ${hue}/${dark}/${fg}/${bg}: ${contrast}`);
  }
}
const images=[], props={}, screen={dataset:{},style:{setProperty:(k,v)=>props[k]=v,removeProperty:k=>delete props[k]}};
const context={console, document:{documentElement:{dataset:{},style:{}},querySelector:()=>screen,createElement:()=>({getContext:()=>({fillStyle:'',drawImage(){},getImageData:()=>({data:context.pixels})})})},matchMedia:()=>({matches:false,addEventListener(){}}),Image:class {constructor(){images.push(this)}}, pixels:[255,0,0,255]};
context.window=context;
vm.runInNewContext(fs.readFileSync(require.resolve('./ui/appearance.js'),'utf8'),context);
const state={theme:'light',visualTheme:'classic',colorMode:'cover'};
context.M3X_APPEARANCE.apply(state,{cover:'first'}); context.M3X_APPEARANCE.apply(state,{cover:'second'});
context.pixels=[0,0,255,255]; images[1].onload(); const current=props['--m3x-accent'];
context.pixels=[255,0,0,255]; images[0].onload(); assert.equal(props['--m3x-accent'],current,'stale result cannot repaint');
context.M3X_APPEARANCE.apply(state,{cover:'second'}); assert.equal(images.length,2,'same cover is reused');
context.M3X_APPEARANCE.apply({...state,visualTheme:'endfield'},{cover:'third'}); assert.equal(images.length,2,'industrial theme does not decode covers');
context.M3X_APPEARANCE.apply(state,{cover:'bad'}); images[2].onerror(); assert.equal(props['--m3x-accent'],palette(26,false).accent);
console.log('PASS: hue extraction, 4320 text/background contrast checks, stale image ordering, cache, failure fallback, Endfield isolation');
