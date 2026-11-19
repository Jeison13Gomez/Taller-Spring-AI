package com.example.chat.impl;

import com.example.chat.GenerationSettings;
import com.example.chat.ModelResult;
import com.example.chat.ModelService;
import com.example.chat.dto.SourceInfo;
import com.example.chat.dto.UsageMetrics;
import com.example.chat.memory.ConversationMemory;
import com.example.chat.prompt.PromptTemplateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementacion de ModelService sobre Spring AI.
 *
 * El starter spring-ai-starter-model-ollama autoconfigura el bean ChatClient.Builder
 * a partir de las propiedades spring.ai.ollama.*, por lo que aqui no hace falta
 * construir clientes HTTP, ni resolver endpoints, ni usar reflexion.
 */
@Service
public class SpringAiModelService implements ModelService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiModelService.class);

    /**
     * Reto 1: plantilla propia (en espanol) del QuestionAnswerAdvisor. Conserva los dos marcadores
     * que el advisor rellena: <query> (la pregunta) y <question_answer_context> (los fragmentos).
     * Usa delimitadores < > para no chocar con llaves de otros textos.
     */
    private static final PromptTemplate RAG_PROMPT = PromptTemplate.builder()
            .renderer(StTemplateRenderer.builder()
                    .startDelimiterToken('<')
                    .endDelimiterToken('>')
                    .build())
            .template("""
                    <query>

                    Contexto del documento (entre las lineas de guiones):

                    ---------------------
                    <question_answer_context>
                    ---------------------

                    Responde en espanol usando SOLO la informacion del contexto anterior,
                    sin usar tu conocimiento previo. Si la respuesta no esta en el contexto,
                    responde exactamente: "No encuentro esa informacion en el documento."
                    """)
            .build();

    private final ChatClient chatClient;
    private final PromptTemplateRegistry templates;
    private final ConversationMemory conversationMemory;

    public SpringAiModelService(ChatClient.Builder chatClientBuilder, PromptTemplateRegistry templates,
                                ConversationMemory conversationMemory, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .promptTemplate(RAG_PROMPT)
                                .searchRequest(SearchRequest.builder()
                                        .topK(6)                    // cuantos fragmentos recuperar (Reto 2, config C)
                                        .similarityThreshold(0.5)   // similitud minima (0 a 1)
                                        .build())
                                .build(),
                        // Temporal: muestra en el log el prompt real que recibe el modelo.
                        new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE - 1))
                .build();
        this.templates = templates;
        this.conversationMemory = conversationMemory;
    }

    @Override
    public ModelResult generateResponse(String question, String sessionId, GenerationSettings settings, String templateId) {
        String effectiveTemplate = (templateId == null || templateId.isEmpty())
                ? PromptTemplateRegistry.DEFAULT_ID
                : templateId;
        log.debug("Enviando prompt al modelo. sessionId={} longitud={} plantilla={} parametros={}",
                sessionId, question.length(), PromptTemplateRegistry.abbreviate(effectiveTemplate), settings);

        // Sin sessionId (null) no hay memoria: ni asesor, ni candado, ni historial (como antes de la feature 006).
        if (sessionId == null) {
            return ask(question, sessionId, settings, effectiveTemplate, null);
        }
        // Con sessionId el turno entero (peticion y respuesta) va dentro del candado de la sesion; si falla
        // o la respuesta viene en blanco, ConversationMemory deja el historial como estaba.
        return conversationMemory.runTurn(sessionId,
                (memoryAdvisor, historyMessages) -> ask(question, sessionId, settings, effectiveTemplate, memoryAdvisor));
    }

    /** Construye y lanza la peticion; memoryAdvisor es null cuando la peticion no lleva sesion. */
    private ModelResult ask(String question, String sessionId, GenerationSettings settings,
                            String effectiveTemplate, Advisor memoryAdvisor) {
        // La plantilla ya viene renderizada (con sus llaves JSON, si las tiene): el renderer de la
        // peticion no hace nada para que ni ese texto ni la pregunta se interpreten como plantilla.
        // El mensaje de sistema es el de la plantilla de ESTE turno: el asesor lo deja el primero y
        // nunca lo guarda, asi que cada turno aplica su plantilla sin duplicarla en el historial.
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .system(templates.render(effectiveTemplate))
                .user(question)
                .templateRenderer(new NoOpTemplateRenderer());
        if (memoryAdvisor != null) {
            request = request.advisors(a -> a.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, sessionId));
        }
        // Solo los valores enviados; el resto conserva los por defecto de spring.ai.ollama.chat.*.
        OllamaChatOptions.Builder options = toOptionsBuilder(settings);
        if (options != null) {
            request = request.options(options);
        }

        // Nombre completo: el DTO com.example.chat.dto.ChatResponse comparte nombre simple.
        // chatClientResponse() (en vez de chatResponse()) expone el contexto del advisor, donde
        // QuestionAnswerAdvisor deja los documentos recuperados (Reto 3).
        ChatClientResponse clientResponse = request.call().chatClientResponse();
        org.springframework.ai.chat.model.ChatResponse response = clientResponse.chatResponse();
        List<SourceInfo> sources = extractSources(clientResponse);

        String answer = (response == null || response.getResult() == null)
                ? null
                : response.getResult().getOutput().getText();

        if (answer == null || answer.isBlank()) {
            // El ChatController traduce una respuesta en blanco a HTTP 502 + status "error".
            log.warn("El modelo devolvio una respuesta vacia. sessionId={}", sessionId);
            return new ModelResult("", null);
        }

        log.debug("Respuesta recibida. sessionId={} longitud={}", sessionId, answer.length());
        return new ModelResult(answer, toUsageMetrics(response), 0, sources);
    }

    /**
     * Reto 3: lee los fragmentos que el QuestionAnswerAdvisor recupero (clave RETRIEVED_DOCUMENTS) y
     * los convierte en archivo de origen (metadato "fuente") + extracto de 200 caracteres.
     */
    static List<SourceInfo> extractSources(ChatClientResponse clientResponse) {
        Object retrieved = clientResponse == null || clientResponse.context() == null
                ? null
                : clientResponse.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(retrieved instanceof List<?> documents)) {
            return List.of();
        }
        List<SourceInfo> sources = new ArrayList<>();
        for (Object item : documents) {
            if (item instanceof Document document) {
                Object fuente = document.getMetadata().get("fuente");
                if (fuente == null) {
                    fuente = document.getMetadata().get("source");
                }
                String text = document.getText() == null ? "" : document.getText().replaceAll("\\s+", " ").trim();
                String excerpt = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                sources.add(new SourceInfo(fuente == null ? "desconocido" : fuente.toString(), excerpt));
            }
        }
        return sources;
    }

    /**
     * Convierte los parametros de la peticion en opciones de Ollama. Devuelve null si no se envio
     * ninguno; en otro caso solo se fijan los campos no nulos, de modo que 0 / 0.0 si se aplican.
     */
    static OllamaChatOptions.Builder toOptionsBuilder(GenerationSettings settings) {
        if (settings == null || settings.isEmpty()) {
            return null;
        }
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder();
        if (settings.temperature() != null) {
            builder.temperature(settings.temperature());
        }
        if (settings.topP() != null) {
            builder.topP(settings.topP());
        }
        if (settings.topK() != null) {
            builder.topK(settings.topK());
        }
        if (settings.numPredict() != null) {
            builder.numPredict(settings.numPredict());
        }
        if (settings.seed() != null) {
            builder.seed(settings.seed());
        }
        return builder;
    }

    /**
     * Extrae las metricas de uso de la respuesta de Spring AI. Todo valor que el modelo no
     * aporte queda en null ("no disponible"); nunca se estima ni se sustituye por 0.
     * La extraccion de un intento esta en AttemptUsage y la suma de varios en UsageAggregator.
     */
    static UsageMetrics toUsageMetrics(org.springframework.ai.chat.model.ChatResponse response) {
        return UsageAggregator.sum(List.of(AttemptUsage.from(response)));
    }
}