package graphql.servlet

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.execution.reactive.SingleSubscriberPublisher
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.servlet.context.DefaultGraphQLContext
import graphql.servlet.context.GraphQLContext
import graphql.servlet.context.GraphQLContextBuilder
import graphql.servlet.context.ContextSetting
import org.dataloader.BatchLoader
import org.dataloader.DataLoader
import org.dataloader.DataLoaderRegistry
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Shared
import spock.lang.Specification

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.Part
import javax.websocket.Session
import javax.websocket.server.HandshakeRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DataLoaderDispatchingSpec extends Specification {

    public static final int STATUS_OK = 200
    public static final String CONTENT_TYPE_JSON_UTF8 = 'application/json;charset=UTF-8'

    @Shared
    ObjectMapper mapper = new ObjectMapper()

    AbstractGraphQLHttpServlet servlet
    MockHttpServletRequest request
    MockHttpServletResponse response
    AtomicInteger fetchCounterA = new AtomicInteger()
    AtomicInteger loadCounterA = new AtomicInteger()
    AtomicInteger fetchCounterB = new AtomicInteger()
    AtomicInteger loadCounterB = new AtomicInteger()
    AtomicInteger fetchCounterC = new AtomicInteger()
    AtomicInteger loadCounterC = new AtomicInteger()

    BatchLoader<String, String> batchLoaderWithCounter(AtomicInteger fetchCounter) {
        return new BatchLoader<String, String>() {
            @Override
            CompletionStage<List<String>> load(List<String> keys) {
                fetchCounter.incrementAndGet()
                CompletableFuture.completedFuture(keys)
            }
        }
    }

    def registry() {
        DataLoaderRegistry registry = new DataLoaderRegistry()
        registry.register("A", DataLoader.newDataLoader(batchLoaderWithCounter(fetchCounterA)))
        registry.register("B", DataLoader.newDataLoader(batchLoaderWithCounter(fetchCounterB)))
        registry.register("C", DataLoader.newDataLoader(batchLoaderWithCounter(fetchCounterC)))
        registry
    }

    def setup() {
        request = new MockHttpServletRequest()
        request.setAsyncSupported(true)
        request.asyncSupported = true
        response = new MockHttpServletResponse()
    }

    def queryDataFetcher(String dataLoaderName, AtomicInteger loadCounter) {
        return new DataFetcher() {
            @Override
            Object get(DataFetchingEnvironment environment) {
                String id = environment.arguments.arg
                loadCounter.incrementAndGet()
                environment.getDataLoader(dataLoaderName).load(id)
            }
        }
    }

    def contextBuilder () {
        return new GraphQLContextBuilder() {
            @Override
            GraphQLContext build(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Map<String, List<Part>> fileParts) {
                new DefaultGraphQLContext(registry(), null)
            }

            @Override
            GraphQLContext build(Session session, HandshakeRequest handshakeRequest) {
                new DefaultGraphQLContext(registry(), null)
            }

            @Override
            GraphQLContext build() {
                new DefaultGraphQLContext(registry(), null)
            }
        }
    }

    def configureServlet(ContextSetting contextSetting) {
        servlet = TestUtils.createDataLoadingServlet(queryDataFetcher("A", loadCounterA),
                queryDataFetcher("B", loadCounterB), queryDataFetcher("C", loadCounterC),
                false, contextSetting,
                contextBuilder())
    }

    def resetCounters() {
        fetchCounterA.set(0)
        fetchCounterB.set(0)
        loadCounterA.set(0)
        loadCounterB.set(0)
    }

    def "batched query with per query context does not batch loads together"() {
        setup:
        configureServlet(ContextSetting.PER_QUERY)
        request.addParameter('query', '[{ "query": "query { query(arg:\\"test\\") { echo(arg:\\"test\\") { echo(arg:\\"test\\") } }}" }, { "query": "query{query(arg:\\"test\\") { echo (arg:\\"test\\") { echo(arg:\\"test\\")} }}" },' +
                ' { "query": "query{queryTwo(arg:\\"test\\") { echo (arg:\\"test\\")}}" }, { "query": "query{queryTwo(arg:\\"test\\") { echo (arg:\\"test\\")}}" }]')
        resetCounters()

        when:
        servlet.doGet(request, response)

        then:
        response.getStatus() == STATUS_OK
        response.getContentType() == CONTENT_TYPE_JSON_UTF8
        getBatchedResponseContent()[0].data.query.echo.echo == "test"
        getBatchedResponseContent()[1].data.query.echo.echo == "test"
        getBatchedResponseContent()[2].data.queryTwo.echo == "test"
        getBatchedResponseContent()[3].data.queryTwo.echo == "test"
        fetchCounterA.get() == 2
        loadCounterA.get() == 2
        fetchCounterB.get() == 2
        loadCounterB.get() == 2
        fetchCounterC.get() == 2
        loadCounterC.get() == 2
    }

    def "batched query with per request context batches all queries within the request"() {
        setup:
        servlet = configureServlet(ContextSetting.PER_REQUEST)
        request.addParameter('query', '[{ "query": "query { query(arg:\\"test\\") { echo(arg:\\"test\\") { echo(arg:\\"test\\") } }}" }, { "query": "query{query(arg:\\"test\\") { echo (arg:\\"test\\") { echo(arg:\\"test\\")} }}" },' +
                ' { "query": "query{queryTwo(arg:\\"test\\") { echo (arg:\\"test\\")}}" }, { "query": "query{queryTwo(arg:\\"test\\") { echo (arg:\\"test\\")}}" }]')
        resetCounters()

        when:
        servlet.doGet(request, response)

        then:
        response.getStatus() == STATUS_OK
        response.getContentType() == CONTENT_TYPE_JSON_UTF8
        getBatchedResponseContent()[0].data.query.echo.echo == "test"
        getBatchedResponseContent()[1].data.query.echo.echo == "test"
        getBatchedResponseContent()[2].data.queryTwo.echo == "test"
        getBatchedResponseContent()[3].data.queryTwo.echo == "test"
        fetchCounterA.get() == 1
        loadCounterA.get() == 2
        fetchCounterB.get() == 1
        loadCounterB.get() == 2
        fetchCounterC.get() == 1
        loadCounterC.get() == 2
    }

    List<Map<String, Object>> getBatchedResponseContent() {
        mapper.readValue(response.getContentAsByteArray(), List)
    }
}
