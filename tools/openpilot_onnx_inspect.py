"""Dump the real structure of an ONNX graph: inputs, outputs, layer mix, weight size.

Written to inspect openpilot's driving_supercombo.onnx rather than trusting
modeld.py's guesswork about what the network is.
"""
import base64
import collections
import sys

import onnx

DT = onnx.TensorProto.DataType


def shape_of(value_info):
    tensor = value_info.type.tensor_type
    dims = []
    for dim in tensor.shape.dim:
        if dim.dim_value:
            dims.append(dim.dim_value)
        elif dim.dim_param:
            dims.append(dim.dim_param)
        else:
            dims.append("?")
    return dims, DT.Name(tensor.elem_type)


def main(path):
    model = onnx.load(path, load_external_data=False)
    graph = model.graph

    print("=== file ===")
    print(f"  {path}")
    print(f"  ir_version      {model.ir_version}")
    print(f"  producer        {model.producer_name} {model.producer_version}")
    print(f"  license         {getattr(model, 'license', '') or '-'}")
    for op in model.opset_import:
        print(f"  opset           {op.domain or 'ai.onnx'} {op.version}")
    if model.doc_string:
        print(f"  doc_string      {model.doc_string[:300]}")

    initializer_names = {init.name for init in graph.initializer}

    print("\n=== graph inputs (real, non-weight) ===")
    for value in graph.input:
        if value.name in initializer_names:
            continue
        dims, dtype = shape_of(value)
        print(f"  {value.name:<28} {dims} {dtype}")

    print("\n=== graph outputs ===")
    for value in graph.output:
        dims, dtype = shape_of(value)
        print(f"  {value.name:<28} {dims} {dtype}")

    print("\n=== node mix ===")
    counts = collections.Counter(node.op_type for node in graph.node)
    for op_type, count in counts.most_common(25):
        print(f"  {op_type:<30} {count}")
    print(f"  {'TOTAL NODES':<30} {len(graph.node)}")

    print("\n=== weights ===")
    params = 0
    raw_bytes = 0
    for init in graph.initializer:
        count = 1
        for dim in init.dims:
            count *= dim
        params += count
        raw_bytes += len(init.raw_data)
    print(f"  initializers    {len(graph.initializer)}")
    print(f"  parameters      {params:,}  ({params / 1e6:.2f} M)")
    print(f"  fp32 size       {params * 4 / 1048576:.1f} MB")
    print(f"  fp16 size       {params * 2 / 1048576:.1f} MB")
    print(f"  raw_data bytes  {raw_bytes:,}")

    # openpilot's loader reads output slices / metadata out of these properties.
    print("\n=== metadata_props ===")
    if not model.metadata_props:
        print("  (none)")
    for prop in model.metadata_props:
        value = prop.value
        note = ""
        try:
            decoded = base64.b64decode(value, validate=True)
            note = f"  <- base64, {len(decoded)} bytes decoded"
        except Exception:
            pass
        shown = value if len(value) <= 240 else value[:240] + "..."
        print(f"  {prop.key} = {shown}{note}")

    print("\n=== value_info count (intermediate tensors) ===")
    print(f"  {len(graph.value_info)}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "driving_supercombo.onnx")
