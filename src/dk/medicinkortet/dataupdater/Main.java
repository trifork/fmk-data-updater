package dk.medicinkortet.dataupdater;

import java.time.LocalDateTime;
import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.test.context.ContextConfiguration;

import dk.medicinkortet.authentication.SecurityCredentials;
import dk.medicinkortet.authentication.ValidatedRole;
import dk.medicinkortet.requestcontext.RequestContext;
import dk.medicinkortet.services.vo.Permission;
import dk.medicinkortet.services.vo.Role;

@ContextConfiguration("/dk/medicinkortet/dataupdater/applicationcontext.xml")
public class Main {
	
	private static Logger logger = LogManager.getLogger(Main.class);
	
	public static void main(String[] args) {
	
		if(args.length < 2) {
			logger.error("Usage: fmk-data-updater <action> [test|update] [cpr1,cpr2..,cprn]");
			return;
		}
		if(!args[1].equals("test") && !args[1].equals("update")) {
			logger.error("Usage: fmk-data-updater <action> [test|update] [cpr1,cpr2..,cprn]");
			return;
		}
		
		boolean testMode =  (args.length > 1 && args[1].equals("test"));
		String action =  args.length > 0 ? args[0] : null;
		String[] cprs = args.length > 2 ? args[2].split(",") : null;
				
		 RequestContext.create(
	                SecurityCredentials.ACCESS_TYPE_CONSOLE, "dataupdater_job", null, LocalDateTime.now());
			RequestContext.setValidatedRole(ValidatedRole.roleFor(Role.System, Arrays.asList(Permission.Laegemiddelordination, Permission.SundhedsfagligOpslag)));

		ApplicationContext context = new ClassPathXmlApplicationContext(new String[] {"/dk/medicinkortet/dataupdater/applicationcontext.xml"});
		
		if("dosageenddate".equals(action)) {
			DosageEnddateUpdater upd = context.getBean(DosageEnddateUpdater.class);
			upd.update(testMode, cprs);
		} else {
			logger.error("Unknown action parameter " + action);
		}
	
		logger.info("Updater completed - waiting 10 secs to flush logs");
		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			logger.error("Wait failed", e);
		}
		System.exit(0);
	}
}
