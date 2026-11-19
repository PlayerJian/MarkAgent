package com.markagent.docs;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 文档库文件布局（用户隔离，物理目录即隔离边界）：
 * <pre>
 * data/docs/files/{userId}/libraries/{libraryId}/{docId}/
 *   ├── original{suffix}      上传原件（suffix 取小写扩展名）
 *   └── mineru/               解析产物（md + content_list.json + images），冻结只读
 * </pre>
 * 索引目录（M2）在 data/docs/indexes/{userId}/{libraryId}/，不在此布局内。
 */
@Component
public class DocStorageLayout {

    /** 入库支持的文件扩展名（图片暂不支持，后续接视觉走独立管线） */
    public static final Set<String> SUPPORTED = Set.of(
            ".txt", ".md", ".csv", ".json", ".pdf", ".docx", ".xlsx", ".pptx");

    /** 本地直读的文本类扩展名：不进 MinerU，按行建块 */
    public static final Set<String> NATIVE_TEXT = Set.of(".txt", ".md", ".csv", ".json");

    private final Path root;

    public DocStorageLayout(Path storageRoot) {
        this.root = storageRoot.resolve("docs").resolve("files");
    }

    /** 布局根 data/docs/files（启动清理遍历用户目录用） */
    public Path root() {
        return root;
    }

    public Path libraryDir(String userId, String libraryId) {
        return root.resolve(userId).resolve("libraries").resolve(libraryId);
    }

    public Path docDir(String userId, String libraryId, String docId) {
        return libraryDir(userId, libraryId).resolve(docId);
    }

    /** 原件路径：original{suffix}（不保留原始文件名，杜绝路径注入） */
    public Path originalPath(String userId, String libraryId, String docId, String fileName) {
        return docDir(userId, libraryId, docId).resolve("original" + suffix(fileName));
    }

    /** MinerU 产物目录（md/content_list/images 冻结处） */
    public Path mineruDir(String userId, String libraryId, String docId) {
        return docDir(userId, libraryId, docId).resolve("mineru");
    }

    /** 文本类直读的块序列落盘（重启后索引句柄装载用，与 MinerU 产物同生命周期） */
    public Path nativeBlocksPath(String userId, String libraryId, String docId) {
        return docDir(userId, libraryId, docId).resolve("native-blocks.json");
    }

    /** 临时上传目录（单文件问答用）：data/docs/files/{userId}/tmp-uploads/{uploadId}/ */
    public Path tmpUploadsDir(String userId) {
        return root.resolve(userId).resolve("tmp-uploads");
    }

    public Path tmpUploadDir(String userId, String uploadId) {
        return tmpUploadsDir(userId).resolve(uploadId);
    }

    /** 小写扩展名（含点，无扩展名返回空串） */
    public static String suffix(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** 文件名取 basename 并校验（拒绝路径形式/控制字符/空名/超长），非法抛参数错误文本 */
    public static String sanitizeFileName(String original) {
        if (original == null) {
            return null;
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = slash >= 0 ? name.substring(slash + 1) : name;
        if (name.isBlank() || name.length() > 255) {
            throw new IllegalArgumentException("文件名非法：" + original);
        }
        for (char c : name.toCharArray()) {
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("文件名含控制字符：" + original);
            }
        }
        return name;
    }
}
