package io.sentry.spring.tracing

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import io.sentry.IHub
import io.sentry.Scope
import io.sentry.SentryOptions
import io.sentry.SentryTransaction
import io.sentry.SpanContext
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import java.lang.RuntimeException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.aopalliance.aop.Advice
import org.junit.runner.RunWith
import org.springframework.aop.Advisor
import org.springframework.aop.Pointcut
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.test.context.junit4.SpringRunner

@RunWith(SpringRunner::class)
@SpringJUnitConfig(SentrySpanAdviceTest.Config::class)
class SentrySpanAdviceTest {

    @Autowired
    lateinit var sampleService: SampleService

    @Autowired
    lateinit var hub: IHub

    @BeforeTest
    fun setup() {
        whenever(hub.startTransaction(any<TransactionContext>())).thenAnswer { SentryTransaction(it.arguments[0] as String, SpanContext(), hub) }
    }

    @Test
    fun `when method is annotated with @SentrySpan with properties set, attaches span to existing transaction`() {
        val scope = Scope(SentryOptions())
        val tx = SentryTransaction("aTransaction", SpanContext(), hub)
        scope.setTransaction(tx)

        whenever(hub.span).thenReturn(tx)
        val result = sampleService.methodWithSpanDescriptionSet()
        assertEquals(1, result)
        assertEquals(1, tx.spans.size)
        assertEquals("customName", tx.spans.first().description)
        assertEquals("bean", tx.spans.first().operation)
    }

    @Test
    fun `when method is annotated with @SentrySpan without properties set, attaches span to existing transaction and sets Span description as className dot methodName`() {
        val scope = Scope(SentryOptions())
        val tx = SentryTransaction("aTransaction", SpanContext(), hub)
        scope.setTransaction(tx)

        whenever(hub.span).thenReturn(tx)
        val result = sampleService.methodWithoutSpanDescriptionSet()
        assertEquals(2, result)
        assertEquals(1, tx.spans.size)
        assertEquals("SampleService.methodWithoutSpanDescriptionSet", tx.spans.first().description)
        assertNull(tx.spans.first().operation)
    }

    @Test
    fun `when method is annotated with @SentrySpan and returns, attached span has status OK`() {
        val scope = Scope(SentryOptions())
        val tx = SentryTransaction("aTransaction", SpanContext(), hub)
        scope.setTransaction(tx)

        whenever(hub.span).thenReturn(tx)
        sampleService.methodWithSpanDescriptionSet()
        assertEquals(SpanStatus.OK, tx.spans.first().status)
    }

    @Test
    fun `when method is annotated with @SentrySpan and throws exception, attached span has throwable set and INTERNAL_ERROR status`() {
        val scope = Scope(SentryOptions())
        val tx = SentryTransaction("aTransaction", SpanContext(), hub)
        scope.setTransaction(tx)

        whenever(hub.span).thenReturn(tx)
        var throwable: Throwable? = null
        try {
            sampleService.methodThrowingException()
        } catch (e: Exception) {
            throwable = e
        }
        assertEquals(SpanStatus.INTERNAL_ERROR, tx.spans.first().status)
        assertEquals(throwable, tx.spans.first().throwable)
    }

    @Test
    fun `when method is annotated with @SentrySpan and there is no active transaction, span is not created and method is executed`() {
        Scope(SentryOptions())
        whenever(hub.span).thenReturn(null)
        val result = sampleService.methodWithSpanDescriptionSet()
        assertEquals(1, result)
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    open class Config {

        @Bean
        open fun sampleService() = SampleService()

        @Bean
        open fun hub() = mock<IHub>()

        @Bean
        open fun sentrySpanPointcut(): Pointcut {
            return AnnotationMatchingPointcut(null, SentrySpan::class.java)
        }

        @Bean
        open fun sentrySpanAdvice(hub: IHub): Advice {
            return SentrySpanAdvice(hub)
        }

        @Bean
        open fun sentrySpanAdvisor(hub: IHub): Advisor {
            return DefaultPointcutAdvisor(sentrySpanPointcut(), sentrySpanAdvice(hub))
        }
    }

    open class SampleService {

        @SentrySpan(description = "customName", operation = "bean")
        open fun methodWithSpanDescriptionSet() = 1

        @SentrySpan
        open fun methodWithoutSpanDescriptionSet() = 2

        @SentrySpan
        open fun methodThrowingException() {
            throw RuntimeException("ex")
        }
    }
}
