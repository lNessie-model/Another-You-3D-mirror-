import com.mirror.bench.AvatarAsset;
import com.mirror.bench.AvatarGlbLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.json.JSONObject;

/** Author-time budget gate using the actual production GLB decoder. */
class AvatarAssetBudgetCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected GLB and fresh report");
        Path report = Path.of(args[1]);
        if (Files.exists(report)) throw new IllegalArgumentException("Preserve previous evidence");
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        AvatarAsset asset = AvatarGlbLoader.load(bytes);
        int primitives = 0, draws = 0;
        for (AvatarAsset.Mesh mesh : asset.meshes()) primitives += mesh.primitives().size();
        for (AvatarAsset.Node node : asset.nodes()) {
            if (node.meshIndex() >= 0) draws += asset.meshes().get(node.meshIndex()).primitives().size();
        }
        boolean passed = bytes.length < 32*1024*1024 && asset.vertexCount() < 20_000
            && asset.triangleCount() <= 30_000 && primitives <= 8 && draws <= 8
            && asset.decodedBytes() < 80L*1024*1024;
        JSONObject data = new JSONObject().put("passed", passed)
            .put("modelSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .put("fileBytes", bytes.length).put("decodedBytes", asset.decodedBytes())
            .put("vertices", asset.vertexCount()).put("triangles", asset.triangleCount())
            .put("uniquePrimitives", primitives).put("draws", draws)
            .put("scope", "Actual production AvatarGlbLoader; decoded budget excludes input file and JSON DOM");
        Files.writeString(report, data.toString(2));
        if (!passed) throw new AssertionError(data.toString());
    }
}
