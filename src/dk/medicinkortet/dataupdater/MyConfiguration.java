package dk.medicinkortet.dataupdater;

import javax.servlet.ServletContext;

import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MyConfiguration {
	
	MockServletContext servletContext = new MockServletContext();
	
	@Bean
	public ServletContext servletContext() {
		return servletContext;
	}
}
