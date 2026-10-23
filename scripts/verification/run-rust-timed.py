#!/usr/bin/env python3
"""复用 cargo 产物，逐 Rust 用例计时；单元与集成分别输出 JSON。"""
import argparse, json, pathlib, subprocess, time

parser=argparse.ArgumentParser()
parser.add_argument('--output',required=True)
parser.add_argument('--layer',choices=['unit','integration'],required=True)
args=parser.parse_args()
selector=['--lib'] if args.layer=='unit' else ['--test','pro_tuner_features','--test','practice_workflow','--test','input_workflow']
build=subprocess.run(['cargo','test','-j','2','--no-run','--message-format=json',*selector],text=True,capture_output=True)
if build.returncode:
 print(build.stderr);raise SystemExit(build.returncode)
executables=[]
for line in build.stdout.splitlines():
 try:item=json.loads(line)
 except json.JSONDecodeError:continue
 if item.get('reason')=='compiler-artifact' and item.get('executable') and item.get('profile',{}).get('test'):
  executables.append(item['executable'])
results=[]
for executable in executables:
 listing=subprocess.check_output([executable,'--list'],text=True)
 for line in listing.splitlines():
  if not line.endswith(': test'):continue
  name=line[:-6];started=time.monotonic()
  run=subprocess.run([executable,'--exact',name,'--nocapture'],text=True,capture_output=True)
  results.append(dict(layer=args.layer,name=name,seconds=time.monotonic()-started,status='passed' if run.returncode==0 else 'failed',output=run.stdout+run.stderr))
  print(f"{results[-1]['status']} {name} {results[-1]['seconds']:.4f}s",flush=True)
out=pathlib.Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(results,ensure_ascii=False,indent=2))
raise SystemExit(1 if any(x['status']=='failed' for x in results) else 0)
