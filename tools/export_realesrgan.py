"""Export the official x4plus RRDBNet weights; do not run untrusted pickle weights."""
import argparse
import numpy as np
import onnx
import onnxruntime as ort
import torch
from basicsr.archs.rrdbnet_arch import RRDBNet

parser = argparse.ArgumentParser()
parser.add_argument("weights")
parser.add_argument("output")
args = parser.parse_args()
model = RRDBNet(num_in_ch=3, num_out_ch=3, num_feat=64, num_block=23, num_grow_ch=32, scale=4)
checkpoint = torch.load(args.weights, map_location="cpu", weights_only=True)
model.load_state_dict(checkpoint.get("params_ema", checkpoint.get("params", checkpoint)), strict=True)
model.eval()
sample = torch.rand(1, 3, 96, 96)
with torch.no_grad():
    expected = model(sample).numpy()
    torch.onnx.export(model, sample, args.output, input_names=["input"], output_names=["output"], dynamic_axes={"input": {2: "height", 3: "width"}, "output": {2: "height4", 3: "width4"}}, opset_version=17)
onnx.checker.check_model(onnx.load(args.output))
actual = ort.InferenceSession(args.output, providers=["CPUExecutionProvider"]).run(None, {"input": sample.numpy()})[0]
np.testing.assert_allclose(expected, actual, rtol=2e-3, atol=2e-3)
print("Validated x4 RGB ONNX output:", actual.shape)
