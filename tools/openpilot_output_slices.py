"""Decode the `output_slices` metadata inside openpilot's driving_supercombo.onnx.

The graph has a single flat output (`outputs` [1, 2576]); the meaning of those
2576 numbers lives in a base64-encoded pickle in the model's metadata.
Unpickling is restricted to slice/dict/simple builtins so a hostile model file
cannot execute code here.
"""
import base64
import io
import pickle
import sys

import onnx


class RestrictedUnpickler(pickle.Unpickler):
    ALLOWED = {
        "builtins": {"slice", "dict", "list", "tuple", "int", "float", "str", "bytes"},
    }

    def find_class(self, module, name):
        if name in self.ALLOWED.get(module, set()):
            return getattr(__import__(module), name)
        raise pickle.UnpicklingError(f"blocked global {module}.{name}")


def main(path):
    model = onnx.load(path, load_external_data=False)
    props = {p.key: p.value for p in model.metadata_props}

    for key in ("model_checkpoint",):
        if key in props:
            print(f"{key} = {props[key]}\n")

    blob = base64.b64decode(props["output_slices"])
    slices = RestrictedUnpickler(io.BytesIO(blob)).load()

    # Width of the flat vector, straight from the graph output shape.
    total = 0
    for value in model.graph.output:
        if value.name == "outputs":
            for dim in value.type.tensor_type.shape.dim:
                total = max(total, dim.dim_value)

    # Some entries are open-ended (start:None), so widths are derived from the
    # following entry's offset rather than from the slice itself.
    ordered = sorted(
        slices.items(), key=lambda kv: kv[1].start if kv[1].start is not None else 0
    )

    print(f"=== output_slices: {len(slices)} named outputs packed into one flat vector ===\n")
    print(f"{'name':<26} {'from':>6} {'to':>6}  {'width':>6}")
    print("-" * 50)

    for index, (name, slc) in enumerate(ordered):
        start = slc.start if slc.start is not None else 0
        if slc.stop is not None:
            stop = slc.stop
        elif index + 1 < len(ordered) and ordered[index + 1][1].start is not None:
            stop = ordered[index + 1][1].start
        else:
            stop = total
        print(f"{name:<26} {start:>6} {stop:>6}  {stop - start:>6}")

    print("-" * 50)
    print(f"{'flat output width':<26} {'':>6} {total:>6}")
    print(f"\nmodel_checkpoint = {props.get('model_checkpoint', '-')}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "driving_supercombo.onnx")
