"""Convert the released Ismantic/Handwritten PyTorch checkpoint to FP32 NCNN.

The official release only ships a 4.1 MB INT8 NCNN model.  This script exports
the original FP32 weights (same 3755-class charset, no PTQ accuracy loss) so the
IME can use the larger, more accurate model.
"""
import os
import torch
import pnnx

from model import build_model

CKPT = os.environ.get("HCCR_CKPT", "best.pt")
OUT = os.environ.get("HCCR_OUT", "out")

ck = torch.load(CKPT, map_location="cpu", weights_only=False)
cfg = ck.get("cfg", {})
name = cfg.get("model", "mobilenet_v2")
sd = ck.get("model") or ck.get("state_dict")
classifier_keys = [k for k in sd if "classifier" in k and k.endswith("weight")]
num_classes = sd[classifier_keys[-1]].shape[0] if classifier_keys else 3755
print(f"[convert] model={name} classes={num_classes} epoch={ck.get('epoch')} acc1={ck.get('acc1')}", flush=True)

model = build_model(name, num_classes)
model.load_state_dict(sd)
model.eval()

os.makedirs(OUT, exist_ok=True)
example = torch.randn(1, 1, 64, 64)
base = os.path.join(OUT, "model")
pnnx.export(
    model,
    ptpath=base + ".traced.pt",
    inputs=example,
    pnnxparam=base + ".pnnx.param",
    pnnxbin=base + ".pnnx.bin",
    pnnxpy=base + "_pnnx.py",
    ncnnparam=base + ".ncnn.param",
    ncnnbin=base + ".ncnn.bin",
    ncnnpy=base + "_ncnn.py",
    fp16=False,
)
for f in (base + ".ncnn.param", base + ".ncnn.bin"):
    print(f"[convert] {f}: {os.path.getsize(f)} bytes", flush=True)
