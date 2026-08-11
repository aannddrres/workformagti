package ge.magti.portal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Mirrors main.py's {@code app.mount("/uploads", StaticFiles(directory=settings.UPLOAD_DIR))}:
 * serves whatever {@code ge.magti.portal.web.UploadController} (via
 * {@link PortalProperties#getUploadsDir()}) writes to disk back out over
 * plain HTTP, so an {@code attachment_url}/inline {@code <img src>} of the
 * form {@code /uploads/<uuid>.ext} resolves.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PortalProperties portalProperties;

    public WebConfig(PortalProperties portalProperties) {
        this.portalProperties = portalProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadsDir = java.nio.file.Path.of(portalProperties.getUploadsDir()).toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(uploadsDir);
    }
}
