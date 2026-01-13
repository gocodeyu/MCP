# RAG项目代码详细解析

本文档将结合具体例子，详细解析RAG项目中各个函数的作用和执行结果。

## 项目架构概览

这个RAG项目使用Spring AI框架，主要包含以下组件：
- **Ollama**: 提供大语言模型（LLM）和向量嵌入（Embedding）服务
- **PostgreSQL + pgvector**: 向量数据库，存储文档的向量表示
- **Spring AI**: 提供RAG相关的工具类和接口

---

## 第一部分：文件上传和向量化流程

### 示例场景
假设我们有一个文件 `file.txt`，内容为：
```
王大瓜 1990年出生
```

### 1. 文件读取 - `TikaDocumentReader`

**代码位置**: `RAGTest.java` 第45行

```java
TikaDocumentReader reader = new TikaDocumentReader("./data/file.txt");
List<Document> documents = reader.get();
```

**函数作用**:
- `TikaDocumentReader`: 使用Apache Tika库读取文件，支持多种格式（txt, pdf, docx等）
- `reader.get()`: 读取文件内容并转换为Spring AI的Document对象

**执行结果**:
```java
// documents 是一个 List<Document>，包含1个Document对象
Document {
    id: "自动生成的唯一ID",
    content: "王大瓜 1990年出生",  // 文件的原始文本内容
    metadata: {
        // Tika自动提取的元数据，如文件类型、创建时间等
    }
}
```

**关键点**: 
- 一个文件可能被读取成一个或多个Document对象
- Document包含文本内容和元数据

---

### 2. 文本切分 - `TokenTextSplitter`

**代码位置**: `RAGTest.java` 第48行

```java
List<Document> documentSplitterList = tokenTextSplitter.apply(documents);
```

**函数作用**:
- `tokenTextSplitter.apply()`: 将长文本按照token数量切分成多个小片段
- 默认切分策略：按token数量切分，每个片段有重叠部分（overlap）

**执行过程**:
假设原始文档是：
```
"王大瓜 1990年出生。他是一名程序员，擅长Java开发。"
```

切分后可能变成：
```java
// documentSplitterList 包含多个Document对象
[
    Document {
        content: "王大瓜 1990年出生。他是一名程序员，",
        metadata: {...}
    },
    Document {
        content: "他是一名程序员，擅长Java开发。",
        metadata: {...}
    }
]
```

**为什么需要切分？**
- 向量模型对输入长度有限制
- 切分后可以更精确地检索相关片段
- 提高检索的准确性和效率

**执行结果**:
- 输入：1个Document（原始文件）
- 输出：N个Document（切分后的片段），N >= 1

---

### 3. 添加元数据

**代码位置**: `RAGTest.java` 第50-51行

```java
documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", "知识库1"));
```

**函数作用**:
- 为每个Document添加自定义元数据标签
- 用于后续检索时过滤特定知识库

**执行结果**:
```java
// 每个Document的metadata中增加了knowledge字段
Document {
    content: "王大瓜 1990年出生",
    metadata: {
        "knowledge": "知识库1",  // 新增的标签
        // ... 其他元数据
    }
}
```

**用途**: 
- 可以区分不同来源的知识库
- 检索时可以只搜索特定知识库的内容

---

### 4. 向量化和存储 - `pgVectorStore.accept()`

**代码位置**: `RAGTest.java` 第53行

```java
pgVectorStore.accept(documentSplitterList);
```

**函数作用**:
这是整个流程的核心步骤，内部执行以下操作：

#### 4.1 向量化（Embedding）
```java
// 内部执行流程（简化版）
for (Document doc : documentSplitterList) {
    // 1. 调用OllamaEmbeddingClient将文本转换为向量
    float[] vector = embeddingClient.embed(doc.getContent());
    // 结果: [0.123, -0.456, 0.789, ..., 0.234]  // 通常是768维或1536维的浮点数组
}
```

**向量化过程**:
- 使用 `OllamaEmbeddingClient`（配置在 `OllamaConfig.java` 第40-41行）
- 模型：`nomic-embed-text`
- 将文本 "王大瓜 1990年出生" 转换为数值向量

**执行结果示例**:
```java
文本: "王大瓜 1990年出生"
↓ (通过nomic-embed-text模型)
向量: [0.123, -0.456, 0.789, 0.234, ..., 0.567]  // 768维向量
```

#### 4.2 存储到PostgreSQL
```java
// 内部执行（简化版）
INSERT INTO vector_store (id, content, embedding, metadata) 
VALUES (
    'doc-id-1',
    '王大瓜 1990年出生',
    '[0.123, -0.456, 0.789, ...]',  // 向量数据
    '{"knowledge": "知识库1"}'      // JSON格式的元数据
);
```

**数据库表结构**（pgvector自动创建）:
```sql
CREATE TABLE vector_store (
    id VARCHAR PRIMARY KEY,
    content TEXT,                    -- 原始文本内容
    embedding vector(768),           -- 向量数据（维度取决于模型）
    metadata JSONB                   -- 元数据（JSON格式）
);
```

**执行结果**:
- 每个Document的文本被转换为向量
- 向量、文本内容和元数据一起存储到PostgreSQL
- 数据库中可以执行向量相似度搜索

---

## 第二部分：对话查询流程

### 示例场景
用户提问："王大瓜，哪年出生？"

### 1. 构建搜索请求 - `SearchRequest`

**代码位置**: `RAGTest.java` 第70行

```java
String message = "王大瓜，哪年出生";
SearchRequest request = SearchRequest.query(message)
    .withTopK(5)
    .withFilterExpression("knowledge == '知识库1'");
```

**函数作用**:
- `SearchRequest.query(message)`: 创建搜索请求，将用户问题作为查询文本
- `withTopK(5)`: 返回最相似的5个文档片段
- `withFilterExpression()`: 添加过滤条件，只搜索"知识库1"的内容

**执行结果**:
```java
SearchRequest {
    query: "王大瓜，哪年出生",
    topK: 5,
    filterExpression: "knowledge == '知识库1'"
}
```

---

### 2. 向量相似度搜索 - `pgVectorStore.similaritySearch()`

**代码位置**: `RAGTest.java` 第72行

```java
List<Document> documents = pgVectorStore.similaritySearch(request);
```

**函数作用**:
执行向量相似度搜索，内部流程：

#### 2.1 将查询文本向量化
```java
// 内部执行
String query = "王大瓜，哪年出生";
float[] queryVector = embeddingClient.embed(query);
// 结果: [0.234, -0.567, 0.890, ..., 0.123]  // 与文档向量相同维度
```

#### 2.2 在数据库中执行向量相似度搜索
```sql
-- 内部执行的SQL（简化版）
SELECT 
    id, 
    content, 
    metadata,
    embedding <-> '[0.234, -0.567, ...]' AS distance  -- <-> 是pgvector的余弦距离运算符
FROM vector_store
WHERE metadata->>'knowledge' = '知识库1'  -- 过滤条件
ORDER BY distance ASC                    -- 按相似度排序
LIMIT 5;                                 -- 返回前5个
```

**相似度计算原理**:
- 使用余弦相似度（cosine similarity）或欧氏距离
- 向量越相似，距离越小
- 查询向量 "王大瓜，哪年出生" 与存储的向量 "王大瓜 1990年出生" 相似度很高

**执行结果**:
```java
// documents 是一个 List<Document>，包含最相关的文档片段
[
    Document {
        id: "doc-id-1",
        content: "王大瓜 1990年出生",  // 最相关的片段
        metadata: {"knowledge": "知识库1"},
        // 内部还包含相似度分数（distance）
    },
    // ... 可能还有其他相关片段（如果topK > 1）
]
```

---

### 3. 合并检索到的文档内容

**代码位置**: `RAGTest.java` 第73行

```java
String documentsCollectors = documents.stream()
    .map(Document::getContent)
    .collect(Collectors.joining());
```

**函数作用**:
- 将多个Document的文本内容提取出来
- 使用 `Collectors.joining()` 连接成一个字符串

**执行结果**:
```java
// documentsCollectors 是一个字符串
"王大瓜 1990年出生"
// 如果有多个文档，可能是：
"王大瓜 1990年出生。他是一名程序员，擅长Java开发。"
```

---

### 4. 构建系统提示词 - `SystemPromptTemplate`

**代码位置**: `RAGTest.java` 第62-68行和第75行

```java
String SYSTEM_PROMPT = """
    Use the information from the DOCUMENTS section to provide accurate answers 
    but act as if you knew this information innately.
    If unsure, simply state that you don't know.
    Another thing you need to note is that your reply must be in Chinese!
    DOCUMENTS:
        {documents}
    """;

Message ragMessage = new SystemPromptTemplate(SYSTEM_PROMPT)
    .createMessage(Map.of("documents", documentsCollectors));
```

**函数作用**:
- `SystemPromptTemplate`: 模板类，用于创建系统提示词
- `{documents}`: 占位符，会被替换为检索到的文档内容
- `createMessage()`: 替换占位符，创建系统消息

**执行结果**:
```java
// ragMessage 是一个 SystemMessage 对象
Message {
    type: SYSTEM,
    content: """
        Use the information from the DOCUMENTS section to provide accurate answers 
        but act as if you knew this information innately.
        If unsure, simply state that you don't know.
        Another thing you need to note is that your reply must be in Chinese!
        DOCUMENTS:
            王大瓜 1990年出生
        """
}
```

**提示词的作用**:
- 告诉LLM使用提供的文档内容回答问题
- 要求用中文回答
- 如果不知道答案，直接说不知道

---

### 5. 构建对话消息列表

**代码位置**: `RAGTest.java` 第77-79行

```java
ArrayList<Message> messages = new ArrayList<>();
messages.add(new UserMessage(message));
messages.add(ragMessage);
```

**函数作用**:
- 创建消息列表，包含用户问题和系统提示词
- 消息顺序很重要：先系统提示词，后用户问题（或相反，取决于模型要求）

**执行结果**:
```java
// messages 是一个消息列表
[
    UserMessage {
        content: "王大瓜，哪年出生"
    },
    SystemMessage {
        content: "Use the information from the DOCUMENTS section...\nDOCUMENTS:\n王大瓜 1990年出生"
    }
]
```

---

### 6. 调用LLM生成回答 - `ollamaChatClient.call()`

**代码位置**: `RAGTest.java` 第81行

```java
ChatResponse chatResponse = ollamaChatClient.call(
    new Prompt(messages, OllamaOptions.create().withModel("deepseek-r1:1.5b"))
);
```

**函数作用**:
- `ollamaChatClient.call()`: 调用Ollama的聊天接口
- `Prompt`: 封装消息列表和模型参数
- `OllamaOptions.create().withModel()`: 指定使用的模型

**执行过程**:
1. 将消息列表发送到Ollama服务（http://localhost:11434）
2. Ollama使用 `deepseek-r1:1.5b` 模型处理请求
3. 模型根据系统提示词和用户问题生成回答

**模型处理逻辑**:
```
输入:
  System: "使用以下文档回答问题：王大瓜 1990年出生"
  User: "王大瓜，哪年出生？"

模型推理:
  - 从文档中提取信息：王大瓜 1990年出生
  - 理解用户问题：询问出生年份
  - 生成回答：1990年

输出:
  "王大瓜出生于1990年。"
```

**执行结果**:
```java
// chatResponse 包含模型的回答
ChatResponse {
    result: {
        output: {
            content: "王大瓜出生于1990年。"
        }
    },
    metadata: {
        // 模型相关的元数据，如token使用量等
    }
}
```

---

## 第三部分：配置类解析

### `OllamaConfig.java` - 核心配置

#### 1. OllamaApi Bean
```java
@Bean
public OllamaApi ollamaApi(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
    return new OllamaApi(baseUrl);
}
```
**作用**: 创建Ollama API客户端，连接到Ollama服务
**配置值**: `http://111.228.63.216:11434`（从application-dev.yml读取）

#### 2. OllamaChatClient Bean
```java
@Bean
public OllamaChatClient ollamaChatClient(OllamaApi ollamaApi) {
    return new OllamaChatClient(ollamaApi);
}
```
**作用**: 创建聊天客户端，用于调用LLM生成回答

#### 3. TokenTextSplitter Bean
```java
@Bean
public TokenTextSplitter tokenTextSplitter() {
    return new TokenTextSplitter();
}
```
**作用**: 创建文本切分器，用于将长文本切分成小片段

#### 4. PgVectorStore Bean
```java
@Bean
public PgVectorStore pgVectorStore(OllamaApi ollamaApi, JdbcTemplate jdbcTemplate) {
    OllamaEmbeddingClient embeddingClient = new OllamaEmbeddingClient(ollamaApi);
    embeddingClient.withDefaultOptions(OllamaOptions.create().withModel("nomic-embed-text"));
    return new PgVectorStore(jdbcTemplate, embeddingClient);
}
```
**作用**: 
- 创建向量嵌入客户端（使用nomic-embed-text模型）
- 创建PostgreSQL向量存储，用于存储和检索向量

---

## 完整流程总结

### 上传文件流程
```
文件 (file.txt)
  ↓
[TikaDocumentReader] 读取文件
  ↓
Document对象列表 (原始内容)
  ↓
[TokenTextSplitter] 文本切分
  ↓
Document对象列表 (切分后的片段)
  ↓
[添加元数据] 标记知识库
  ↓
[pgVectorStore.accept()] 
  ├─→ [OllamaEmbeddingClient] 向量化
  └─→ [PostgreSQL] 存储向量和文本
  ↓
完成！向量已存入数据库
```

### 对话查询流程
```
用户问题 ("王大瓜，哪年出生")
  ↓
[SearchRequest] 构建搜索请求
  ↓
[pgVectorStore.similaritySearch()]
  ├─→ [OllamaEmbeddingClient] 将问题向量化
  └─→ [PostgreSQL] 向量相似度搜索
  ↓
相关文档片段列表
  ↓
[合并文档内容] 提取文本
  ↓
[SystemPromptTemplate] 构建系统提示词
  ↓
[构建消息列表] UserMessage + SystemMessage
  ↓
[OllamaChatClient.call()] 调用LLM
  ↓
ChatResponse (最终回答)
```

---

## 关键概念解释

### 1. 向量（Embedding）
- **定义**: 将文本转换为数值数组（通常是浮点数数组）
- **维度**: 取决于模型，nomic-embed-text通常是768维
- **作用**: 将语义相似的文本转换为数值上相近的向量
- **示例**: "猫" 和 "猫咪" 的向量会很接近

### 2. 向量相似度搜索
- **原理**: 计算查询向量与存储向量的距离（余弦距离或欧氏距离）
- **结果**: 返回最相似的文档片段
- **优势**: 可以找到语义相关的内容，即使关键词不完全匹配

### 3. RAG（Retrieval-Augmented Generation）
- **检索（Retrieval）**: 从向量库中检索相关文档
- **增强（Augmented）**: 将检索到的文档作为上下文
- **生成（Generation）**: LLM基于上下文生成回答

---

## 常见问题

### Q1: 为什么需要文本切分？
**A**: 
- 向量模型对输入长度有限制
- 切分后可以更精确地定位相关信息
- 提高检索准确性

### Q2: 向量维度是什么意思？
**A**: 
- 向量是一个数值数组，维度就是数组的长度
- 例如768维就是包含768个浮点数的数组
- 维度越高，通常表达能力越强，但计算成本也越高

### Q3: 相似度搜索是如何工作的？
**A**: 
- 将查询文本转换为向量
- 计算查询向量与所有存储向量的距离
- 返回距离最小的（即最相似的）文档

### Q4: 为什么需要系统提示词？
**A**: 
- 告诉LLM使用提供的文档内容
- 控制回答的格式和语言
- 提高回答的准确性和相关性

---

## 代码执行示例

### 完整的上传示例
```java
// 1. 读取文件
TikaDocumentReader reader = new TikaDocumentReader("./data/file.txt");
List<Document> documents = reader.get();
// 结果: [Document(content="王大瓜 1990年出生", metadata={...})]

// 2. 切分文本
List<Document> documentSplitterList = tokenTextSplitter.apply(documents);
// 结果: [Document(content="王大瓜 1990年出生", metadata={...})]  // 文本短，可能不切分

// 3. 添加元数据
documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", "知识库1"));
// 结果: [Document(content="王大瓜 1990年出生", metadata={"knowledge": "知识库1"})]

// 4. 向量化和存储
pgVectorStore.accept(documentSplitterList);
// 执行: 
//   - 文本 → 向量: "王大瓜 1990年出生" → [0.123, -0.456, ...]
//   - 存储到PostgreSQL
// 结果: 数据库中有1条记录
```

### 完整的查询示例
```java
// 1. 构建搜索请求
String message = "王大瓜，哪年出生";
SearchRequest request = SearchRequest.query(message)
    .withTopK(5)
    .withFilterExpression("knowledge == '知识库1'");
// 结果: SearchRequest对象

// 2. 相似度搜索
List<Document> documents = pgVectorStore.similaritySearch(request);
// 执行:
//   - "王大瓜，哪年出生" → 向量 [0.234, -0.567, ...]
//   - 在数据库中搜索最相似的向量
// 结果: [Document(content="王大瓜 1990年出生", metadata={"knowledge": "知识库1"})]

// 3. 合并文档
String documentsCollectors = documents.stream()
    .map(Document::getContent)
    .collect(Collectors.joining());
// 结果: "王大瓜 1990年出生"

// 4. 构建系统提示词
Message ragMessage = new SystemPromptTemplate(SYSTEM_PROMPT)
    .createMessage(Map.of("documents", documentsCollectors));
// 结果: SystemMessage包含完整的提示词

// 5. 构建消息列表
ArrayList<Message> messages = new ArrayList<>();
messages.add(new UserMessage(message));
messages.add(ragMessage);
// 结果: [UserMessage, SystemMessage]

// 6. 调用LLM
ChatResponse chatResponse = ollamaChatClient.call(
    new Prompt(messages, OllamaOptions.create().withModel("deepseek-r1:1.5b"))
);
// 结果: ChatResponse(result.output.content="王大瓜出生于1990年。")
```

---

## 总结

这个RAG项目的核心流程：

1. **上传阶段**: 文件 → 读取 → 切分 → 向量化 → 存储
2. **查询阶段**: 问题 → 向量化 → 相似度搜索 → 检索文档 → 构建提示词 → LLM生成回答

每个函数都有明确的职责，通过Spring AI框架的封装，简化了向量化和检索的复杂操作。

