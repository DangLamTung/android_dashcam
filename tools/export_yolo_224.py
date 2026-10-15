"""Export and optionally prune YOLOv10n to 224x224 for Android HUD.

Usage:
    python tools/export_yolo_224.py
"""
import onnx
from onnx import helper, TensorProto
import numpy as np
import onnxruntime as ort
from ultralytics import YOLO

def export_and_prune():
    print("Exporting yolov10n.pt at 224x224...")
    model = YOLO("yolov10n.pt")
    onnx_path = model.export(format="onnx", imgsz=224, opset=13)

    print("Pruning to 6 automotive classes: person, bicycle, car, motorcycle, bus, truck...")
    onnx_model = onnx.load(onnx_path)
    graph = onnx_model.graph

    # Automotive classes from COCO: 0, 1, 2, 3, 5, 7
    selected_classes = [0, 1, 2, 3, 5, 7]
    indices_tensor = helper.make_tensor(
        name="selected_class_indices",
        data_type=TensorProto.INT64,
        dims=[len(selected_classes)],
        vals=selected_classes,
    )
    graph.initializer.append(indices_tensor)

    # Find concat node that joins bounding boxes and class logits
    concat_node = None
    for n in graph.node:
        if "output0" in n.output:
            concat_node = n
            break

    assert concat_node is not None, "Failed to find output concat node"
    sigmoid_output = concat_node.input[1]

    # Gather only the 6 chosen classes along axis 1
    gather_node = helper.make_node(
        "Gather",
        inputs=[sigmoid_output, "selected_class_indices"],
        outputs=["pruned_classes_sigmoid"],
        axis=1,
        name="gather_automotive_classes",
    )
    graph.node.append(gather_node)
    concat_node.input[1] = "pruned_classes_sigmoid"

    # Update output shape: 4 boxes + 6 classes = 10 channels
    for out in graph.output:
        if out.name == "output0":
            out.type.tensor_type.shape.dim[1].dim_value = 4 + len(selected_classes)

    pruned_path = "app/src/main/assets/yolov10n-224-pruned.onnx"
    onnx.save(onnx_model, pruned_path)
    print(f"Saved pruned model to {pruned_path}")

    # Copy standard 224 model to assets
    standard_dest = "app/src/main/assets/yolov10n-224.onnx"
    import shutil
    shutil.copyfile(onnx_path, standard_dest)
    print(f"Copied full 80-class model to {standard_dest}")

if __name__ == "__main__":
    export_and_prune()
