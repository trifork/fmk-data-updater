package dk.medicinkortet.dataupdater;

import dk.medicinkortet.utils.TimeServiceForTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.testng.AbstractTransactionalTestNGSpringContextTests;
import org.springframework.test.context.web.WebAppConfiguration;

@ContextConfiguration(
        initializers = dk.medicinkortet.spring.initializer.ConfigurationInitializer.class, 
        locations = {
         	"classpath*:/applicationcontext-test.xml",
            "classpath*:/dk/medicinkortet/spring/applicationcontext.xml",
            "classpath*:/dk/medicinkortet/spring/spring-dao.xml",
    	   "classpath*:/dk/medicinkortet/spring/datasource.xml",
    	   "classpath*:/dk/medicinkortet/spring/webservices-facade.xml",
    	   "classpath*:/springtest/test-beans.xml",
    	   "classpath*:/springtest/testfacade.xml",
    	   "classpath*:/dk/medicinkortet/spring/spring-transactions.xml"
    	   /*
    	   "classpath*:/dk/medicinkortet/spring/receptserver.xml",
    	  
    	   "classpath*:/dk/medicinkortet/spring/spring-interceptors.xml",
    	   ,*/
    	  
           //             
                } )
@WebAppConfiguration
public class SpringTest extends AbstractTransactionalTestNGSpringContextTests  { // AbstractTestNGSpringContextTests {
	
	   
   @Autowired
   protected TimeServiceForTest timeService;
}
