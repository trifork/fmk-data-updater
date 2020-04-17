package dk.medicinkortet.dataupdater;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;

import javax.servlet.ServletContext;

@Configuration
public class MyConfiguration {
	
	MockServletContext servletContext = new MockServletContext();
	
	@Bean
	public ServletContext servletContext() {
		return servletContext;
	}
}
