// Reference decodes with magenta.js: node ref.js <node_modules dir> <checkpoint url> <out.json> [n]
const mods = process.argv[2];
const tf = require(mods + '/@tensorflow/tfjs');
const mm = require(mods + '/@magenta/music/node/music_vae.js');
const fs = require('fs');
// Deterministic normal numbers (Box-Muller on a 32-bit LCG), so the same z can be rebuilt anywhere.
let seed = 12345;
function rnd() { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return (seed + 0.5) / 4294967296; }
function normal() { const u = rnd(), v = rnd(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }
(async () => {
  await tf.setBackend('cpu');
  const model = new mm.MusicVAE(process.argv[3]);
  await model.initialize();
  const n = parseInt(process.argv[5] || '6');
  const zs = [];
  for (let i = 0; i < n; i++) { const z = []; for (let k = 0; k < 256; k++) z.push(i === 0 ? 0 : normal()); zs.push(z); }
  const out = [];
  for (const z of zs) {
    const t = await model.decodeTensors(tf.tensor2d([z]), undefined);
    const arr = await t.array();  // [1, 64, 90+90+512]
    const steps = arr[0].map(row => {
      const mel = row.slice(0, 90), bass = row.slice(90, 180), drums = row.slice(180);
      return [mel.indexOf(1), bass.indexOf(1), drums.indexOf(1)];
    });
    out.push({ z, steps });
    t.dispose();
  }
  fs.writeFileSync(process.argv[4], JSON.stringify(out));
  console.log('decoded', out.length);
})().catch(e => { console.error(e); process.exit(1); });
