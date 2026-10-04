"""Inspect the actual task model operator inventory before choosing an NPU port."""
import sys, json, zipfile
from collections import Counter
from pathlib import Path
sys.path.insert(0, str(Path('E:/tripo/native-tools/python-model-tools')))
import tflite

root=Path(__file__).resolve().parents[1]
names={v:k for k,v in vars(tflite.BuiltinOperator).items() if isinstance(v,int)}
report={}
with zipfile.ZipFile(root/'app/src/main/assets/face_landmarker.task') as archive:
    for name in archive.namelist():
        if not name.endswith('.tflite'): continue
        data=archive.read(name)
        model=tflite.Model.GetRootAsModel(data,0)
        codes=[]
        for i in range(model.OperatorCodesLength()):
            code=model.OperatorCodes(i)
            codes.append(code.CustomCode().decode() if code.CustomCode() else names.get(code.BuiltinCode(),str(code.BuiltinCode())))
        graphs=[]
        for i in range(model.SubgraphsLength()):
            graph=model.Subgraphs(i)
            def tensor(index):
                value=graph.Tensors(index)
                return dict(name=value.Name().decode(),shape=[value.Shape(j) for j in range(value.ShapeLength())],type=int(value.Type()))
            graphs.append(dict(inputs=[tensor(graph.Inputs(j)) for j in range(graph.InputsLength())],
                               outputs=[tensor(graph.Outputs(j)) for j in range(graph.OutputsLength())],
                               operators=dict(Counter(codes[graph.Operators(j).OpcodeIndex()] for j in range(graph.OperatorsLength())))))
        report[name]=dict(bytes=len(data),graphs=graphs)
out=Path('E:/tripo/output/hardware-optimization/20261001/face-model-inventory.json')
out.write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
