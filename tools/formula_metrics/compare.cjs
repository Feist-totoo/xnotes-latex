const fs=require('fs');const katex=require('katex');
let input=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
let out=input.map(s=>{s=s.trim();if(s.startsWith('$$')&&s.endsWith('$$'))s=s.slice(2,-2);try{return katex.renderToString(s,{output:'mathml',displayMode:true,throwOnError:true,strict:'ignore',trust:false})}catch(e){return null}});
fs.writeFileSync(process.argv[3],JSON.stringify(out));
