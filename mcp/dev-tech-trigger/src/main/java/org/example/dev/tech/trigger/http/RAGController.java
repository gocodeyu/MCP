package org.example.dev.tech.trigger.http;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.example.dev.tech.api.IRAGService;
import org.example.dev.tech.api.response.Response;
import org.apache.commons.io.FileUtils;
import org.redisson.Redisson;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.ai.document.Document;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

@RestController()
@Slf4j
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController implements IRAGService{
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private PgVectorStore pgVectorStore;
    @Resource
    private SimpleVectorStore simpleVectorStore;
    @Resource
    private OllamaChatClient ollamaChatClient;

    @Override
    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");
        return Response.<List<String>>builder()
                .code("0000")
                .info("调用成功")
                .data(elements)
                .build();

    }
    @Override
    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    public Response<String> uploadFile(@RequestParam String ragTag, @RequestParam("file") List<MultipartFile> files) {
        log.info("上传知识库开始 {}", ragTag);
        for (MultipartFile file : files) {
            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());
            List<Document> documents = documentReader.get();
            List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

            documents.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));
            documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));

            pgVectorStore.accept(documentSplitterList);

        }
        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(ragTag)){
            elements.add(ragTag);
        }

        log.info("上传知识库完成 {}", ragTag);
        return Response.<String>builder().code("0000").info("调用成功").build();
    }


    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    @Override
    public Response<String> analyzeGitRepository(@RequestParam String repoURL, @RequestParam String username, @RequestParam String password)throws Exception{
        log.info("开始解析仓库 {}", repoURL);
        repoURL=repoURL.replace(".git","");
        String localPath = "./cloned-repo";
        String repoProjectName=extractRepoName(repoURL);
        log.info("克隆路径：{}", new File(localPath).getAbsolutePath());
        FileUtils.deleteDirectory(new File(localPath));
        Git git = Git.cloneRepository()
                .setURI(repoURL)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
                .call();

        git.close();
        Files.walkFileTree(Paths.get(localPath), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                log.info("文件路径:{}", file.toString());
                // 过滤 .git 目录下的文件（这类文件多为配置/空文件，无需解析）
                if (file.toString().contains("/.git/") || file.toString().contains("\\.git\\")) {
                    log.info("跳过 .git 目录下的文件: " + file);
                }
                try {
                    TikaDocumentReader reader = new TikaDocumentReader(new PathResource(file));
                    List<Document> documents = reader.get();
                    List<Document> documentSplitterList = tokenTextSplitter.apply(documents);
                    documents.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                    documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                    pgVectorStore.accept(documentSplitterList);
                }catch (Exception e){
                    log.error("解析文件失败：{}", file.getFileName(), e);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.error("访问文件失败：{}", file.getFileName(), exc);
                return FileVisitResult.CONTINUE;
            }
        });
        FileUtils.deleteDirectory(new File(localPath));
        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(repoProjectName)){
            elements.add(repoProjectName);
        }
        log.info("解析仓库完成 {}", repoURL);
        return Response.<String>builder().code("0000").info("调用成功").build();
    }

    private String extractRepoName(String repoURL) {
        String[] parts = repoURL.split("/");
        String repoName = parts[parts.length-1];

        return repoName.replace(".git", "");
    }

}
