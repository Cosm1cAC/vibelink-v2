import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';

// Build from the actual Android adaptive foreground, not the unused legacy icon.
const [resRoot, cindySource, output] = process.argv.slice(2);
if (!resRoot || !cindySource || !output) throw new Error('Usage: node Build-DesktopIcon.mjs <android-res> <prepared-cindy-source> <output>');
const sharp = createRequire(path.resolve(cindySource, 'package.json'))('sharp');
const adaptive = fs.readFileSync(path.join(resRoot,'mipmap-anydpi-v26/ic_launcher.xml'),'utf8');
const foregroundName = adaptive.match(/foreground[^>]*@drawable\/([^"\s]+)/)?.[1];
const backgroundName = adaptive.match(/background[^>]*@color\/([^"\s]+)/)?.[1];
if (!foregroundName || !backgroundName) throw new Error('Unsupported adaptive launcher definition');
const vector = fs.readFileSync(path.join(resRoot, 'drawable', foregroundName+'.xml'),'utf8');
const colors = fs.readFileSync(path.join(resRoot,'values/colors.xml'),'utf8');
const background = colors.match(new RegExp(`<color name="${backgroundName}">([^<]+)</color>`))?.[1];
if (!background || !vector.includes('android:viewportWidth="108"')) throw new Error('Unexpected mobile icon viewport');
const attributes = {pathData:'d',fillColor:'fill',strokeColor:'stroke',strokeWidth:'stroke-width',strokeLineCap:'stroke-linecap',strokeLineJoin:'stroke-linejoin'};
const paths = [...vector.matchAll(/<path\s+([^>]+)\/>/gs)].map(([,source]) => {
  const attrs = Object.entries(attributes).flatMap(([android,svg]) => {
    const value = source.match(new RegExp(`android:${android}="([^"]+)"`))?.[1];
    return value ? [`${svg}="${value}"`] : [];
  });
  if (!source.includes('android:fillColor')) attrs.push('fill="none"');
  return `<path ${attrs.join(' ')}/>`;
}).join('');
// Adaptive launchers expose the central 72 of the 108dp layer. Keep the same
// symbol scale, with a rounded tile independent of each phone launcher's mask.
const svg=`<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><rect x="18" y="18" width="72" height="72" rx="16" fill="${background}"/>${paths}</svg>\n`;
fs.mkdirSync(output,{recursive:true});
fs.writeFileSync(path.join(output,'vibelink-icon.svg'),svg);
await sharp(Buffer.from(svg)).resize(512,512).png().toFile(path.join(output,'vibelink-icon.png'));
const sizes=[16,20,24,32,40,48,64,128,256];
const images=await Promise.all(sizes.map(size=>sharp(Buffer.from(svg)).resize(size,size).png().toBuffer()));
const header=Buffer.alloc(6+16*sizes.length); header.writeUInt16LE(1,2); header.writeUInt16LE(sizes.length,4);
let offset=header.length;
images.forEach((data,index)=>{const p=6+index*16;header[p]=header[p+1]=sizes[index]===256?0:sizes[index];header.writeUInt16LE(1,p+4);header.writeUInt16LE(32,p+6);header.writeUInt32LE(data.length,p+8);header.writeUInt32LE(offset,p+12);offset+=data.length;});
fs.writeFileSync(path.join(output,'vibelink-icon.ico'),Buffer.concat([header,...images]));
const sha=value=>createHash('sha256').update(value).digest('hex');
fs.writeFileSync(path.join(output,'vibelink-icon-source.json'),JSON.stringify({adaptive:'mipmap-anydpi-v26/ic_launcher.xml',foreground:`drawable/${foregroundName}.xml`,background:backgroundName,foregroundSha256:sha(vector),colorsSha256:sha(colors),sizes,sharp:sharp.versions.sharp,svgSha256:sha(svg)},null,2)+'\n');
console.log('Built mobile-derived VibeLink PNG and multi-size ICO');
