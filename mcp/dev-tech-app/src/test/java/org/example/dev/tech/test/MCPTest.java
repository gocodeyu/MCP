package org.example.dev.tech.test;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;


@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class MCPTest {
    @Autowired(required = false)
    @Qualifier("ollamachatClientBuilder")
    private ChatClient.Builder ollamachatClientBuilder;

    @Autowired(required = false)
    @Qualifier("openaichatClientBuilder")
    private ChatClient.Builder openaichatClientBuilder;

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    @Resource
    private OpenAiChatModel openAiChatModel;

    @Test
    public void test_tool(){
        if (openaichatClientBuilder == null) {
            log.warn("openaichatClientBuilder is not available, skipping test. Please ensure OpenAiChatModel bean is configured.");
            return;
        }
        String userInput = "有哪些工具可以使用";
        ChatClient chatClient = openaichatClientBuilder.defaultTools(toolCallbackProvider).build();
        String content = chatClient.prompt(userInput).call().content();
        System.out.println("结果:" + content);
    }
    
    @Test
    public void test(){
        String userInput = "在 D:\\AI_Work 文件夹下，创建BBB.txt文件，填入内容:橙子,你要加油呀，你是最棒的小女孩嘻嘻！";
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
                .defaultTools(toolCallbackProvider)
                .build();
        String content = chatClient.prompt(userInput).call().content();
        System.out.println("结果:" + content);
    }

}
