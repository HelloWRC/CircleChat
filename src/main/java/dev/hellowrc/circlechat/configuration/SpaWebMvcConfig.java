package dev.hellowrc.circlechat.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.Set;

@Configuration
public class SpaWebMvcConfig implements WebMvcConfigurer {
    private static final String STATIC_LOCATION = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC_LOCATION)
                .resourceChain(true)
                .addResolver(new SpaPageResourceResolver());
    }

    private static final class SpaPageResourceResolver extends PathResourceResolver {
        private static final Resource INDEX_PAGE = new ClassPathResource("static/index.html");
        private static final Set<String> SERVER_PATH_PREFIXES = Set.of(
                "api", "ws", "swagger-ui", "v3", "error", "actuator"
        );

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (resourcePath.isBlank()) {
                return readableIndexPage();
            }

            var resource = super.getResource(resourcePath, location);
            if (resource != null) {
                return resource;
            }

            return isSpaRoute(resourcePath) ? readableIndexPage() : null;
        }

        private static boolean isSpaRoute(String resourcePath) {
            var normalizedPath = resourcePath.startsWith("/")
                    ? resourcePath.substring(1)
                    : resourcePath;
            var firstSegmentEnd = normalizedPath.indexOf('/');
            var firstSegment = firstSegmentEnd < 0
                    ? normalizedPath
                    : normalizedPath.substring(0, firstSegmentEnd);
            var lastSegment = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);

            return !SERVER_PATH_PREFIXES.contains(firstSegment) && !lastSegment.contains(".");
        }

        private static Resource readableIndexPage() {
            return INDEX_PAGE.exists() && INDEX_PAGE.isReadable() ? INDEX_PAGE : null;
        }
    }
}
