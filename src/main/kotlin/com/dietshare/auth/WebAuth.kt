package com.dietshare.auth

import com.dietshare.common.ApiException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.HandlerMethod
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Injects the authenticated user's id into a controller parameter. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser

const val USER_ID_ATTRIBUTE = "dietshare.userId"

@Component
class AuthInterceptor(private val jwtProvider: JwtProvider) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true
        val header = request.getHeader("Authorization") ?: throw ApiException.unauthorized()
        if (!header.startsWith("Bearer ")) throw ApiException.unauthorized()
        val userId = jwtProvider.parseUserId(header.removePrefix("Bearer ").trim())
            ?: throw ApiException.unauthorized("로그인이 만료됐어요")
        request.setAttribute(USER_ID_ATTRIBUTE, userId)
        return true
    }
}

@Component
class CurrentUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any = webRequest.getAttribute(USER_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
        ?: throw ApiException.unauthorized()
}

@Configuration
class WebConfig(
    private val authInterceptor: AuthInterceptor,
    private val currentUserArgumentResolver: CurrentUserArgumentResolver,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns("/auth/**", "/health", "/actuator/**", "/error")
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUserArgumentResolver)
    }
}
