package dk.medicinkortet.dataupdater;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.math.BigDecimal;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dk.medicinkortet.dao.vo.ModificateValue;
import dk.medicinkortet.services.impl.ConsentService;
import dk.medicinkortet.services.req_resp.ConsentRequest;
import dk.medicinkortet.services.vo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import dk.medicinkortet.authentication.SecurityCredentials;
import dk.medicinkortet.authentication.ValidatedRole;
import dk.medicinkortet.requestcontext.RequestContext;
import dk.medicinkortet.services.impl.GetDrugMedicationService;
import dk.medicinkortet.services.impl.createdrugmedications.CreateDrugMedicationServiceImpl;
import dk.medicinkortet.services.req_resp.CreateDrugmedicationRequest;
import dk.medicinkortet.services.req_resp.CreateDrugmedicationResponse;
import dk.medicinkortet.services.req_resp.UpdateResponse;
import dk.medicinkortet.services.vo.dosage.DosageDayVO;
import dk.medicinkortet.services.vo.dosage.DosageTimeVO;
import dk.medicinkortet.services.vo.dosage.DosageTimesVO;
import dk.medicinkortet.services.vo.dosage.DosageVO;
import dk.medicinkortet.utils.TestRequestCreator;
import dk.medicinkortet.utils.TestServiceInvocationHelper;
import dk.medicinkortet.utils.TestValueObjectCreator;
import dk.medicinkortet.utils.TimeServiceForTest;
import dk.medicinkortet.services.vo.dosage.DosageDayVO.DosageTimeType;

public class UpdaterTest extends SpringTest {

	@Autowired
	TestRequestCreator testRequestCreator;
	@Autowired
	CreateDrugMedicationServiceImpl createDrugMedicationService;
	@Autowired
	ConsentService consentService;
	@Autowired
	GetDrugMedicationService getDrugMedicationService;
	@Autowired
	TestServiceInvocationHelper invocator;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	DosageEnddateUpdater updater;
	@Autowired
	NonClinicalModificatorRepair nonClinicalRepair;
	@Autowired
	TimeServiceForTest timeService;
	
	@BeforeMethod(alwaysRun = true)
	public void onSetUp() {
	    RequestContext.create(SecurityCredentials.ACCESS_TYPE_CONSOLE, "dosageend_updater_test", null, LocalDateTime.now());
		RequestContext.setValidatedRole(ValidatedRole.roleFor(Role.System, Arrays.asList(Permission.Laegemiddelordination, Permission.SundhedsfagligOpslag)));
	}
	
	@Test
	public void testCalcDosageEndForNotIteratedWithOneDosageDay() throws ParseException {
	
		// ARRANGE
		// Create drugmedication, iteration=0 no dosageenddate
		timeService.freezeTime(LocalDateTime.now().plusDays(-2));
		RequestContext.get().setPersonCPR(TestValueObjectCreator.CPR);
		final CreateDrugmedicationRequest drugMedicationRequest;
        try {
            drugMedicationRequest = testRequestCreator.createDrugMedicationRequest1(TestValueObjectCreator.CPR);
            drugMedicationRequest.setPersonIdentifier(TestValueObjectCreator.CPR);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        
        DosageTimesVO dosageTimeVO = new DosageTimesVO(0, null,
    		new LocalDateOrDateTime(LocalDate.now(), null),
    		new LocalDateOrDateTime(),
    		Arrays.asList(new DosageDayVO(4, null, new DosageTimeVO(BigDecimal.valueOf(1), null, null, DosageTimeType.isMorning, true))));
        
        DosageVO dosageVO = new DosageVO(dosageTimeVO, null, false);
        drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().setDosageVO(dosageVO);
        BeginEndDatesVO beginEndDate = drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().getBeginEndDates();
        drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().setBeginEndDates(new BeginEndDatesVO(beginEndDate.getStartDate(), new LocalDateOrDateTime()));
        
        UpdateResponse<CreateDrugmedicationResponse> response = invocator.createDrugMedication(createDrugMedicationService, drugMedicationRequest);   
        Long dmId = response.getResponse().getElements().iterator().next().getDrugmedicationIdentifier();
        
        // Clear dosageEnd (since it is calculated in the service during write)
        int rowsAffected = jdbcTemplate.update("UPDATE StructuredDosages SET DosageTimesEndDate=NULL WHERE DosagePID IN (SELECT DosagePID FROM DrugMedications WHERE DrugMedicationIdentifier=?)", dmId);
        assertEquals(rowsAffected, 1);
        
        // Make sure the test works
        timeService.freezeTime(LocalDateTime.now());
        DrugMedicationCompleteVO drugMedication1 = invocator.getDrugMedication(getDrugMedicationService, dmId, LocalDateTime.now());
		DosageTimesVO dtvo = drugMedication1.getDrugMedicationOverview().getDosageVO().getDosageTimesCollection().iterator().next();
		assertFalse(dtvo.getEnd().hasAnything());
		
        // ACT
		updater.update(false, null);
        
        // ASSERT
		DrugMedicationCompleteVO drugMedication2 = invocator.getDrugMedication(getDrugMedicationService, dmId, LocalDateTime.now());
		dtvo = drugMedication2.getDrugMedicationOverview().getDosageVO().getDosageTimesCollection().iterator().next();
		assertNotNull(dtvo.getEnd());
		assertTrue(dtvo.getEnd().hasAnything());
        assertEquals(dtvo.getEnd().getDate(), LocalDate.now().plusDays(3));
        
        // Check that the drugmedication version has been updated
        assertNotEquals(drugMedication1.getDrugMedicationOverview().getVersion().getNonSequentialVersion(), drugMedication2.getDrugMedicationOverview().getVersion().getNonSequentialVersion());

	}
	
	@Test
	public void testCalcDosageEndForNotIteratedWithMoreThanOneDosageDay() throws ParseException {
	
		// ARRANGE
		// Create drugmedication, iteration=0 no dosageenddate
		timeService.freezeTime(LocalDateTime.now().plusDays(-2));
		RequestContext.get().setPersonCPR(TestValueObjectCreator.CPR);
		final CreateDrugmedicationRequest drugMedicationRequest;
        try {
            drugMedicationRequest = testRequestCreator.createDrugMedicationRequest1(TestValueObjectCreator.CPR);
            drugMedicationRequest.setPersonIdentifier(TestValueObjectCreator.CPR);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        
        DosageTimesVO dosageTimeVO = new DosageTimesVO(0, null,
    		new LocalDateOrDateTime(LocalDate.now(), null),
    		new LocalDateOrDateTime(),
    		Arrays.asList(new DosageDayVO(4, null, new DosageTimeVO(BigDecimal.valueOf(1), null, null, DosageTimeType.isMorning, true)),
    				new DosageDayVO(5, null, new DosageTimeVO(BigDecimal.valueOf(1), null, null, DosageTimeType.isMorning, true))
    				));
        
        DosageVO dosageVO = new DosageVO(dosageTimeVO, null, false);
        drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().setDosageVO(dosageVO);
        BeginEndDatesVO beginEndDate = drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().getBeginEndDates();
        drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().setBeginEndDates(new BeginEndDatesVO(beginEndDate.getStartDate(), new LocalDateOrDateTime()));
        
        UpdateResponse<CreateDrugmedicationResponse> response = invocator.createDrugMedication(createDrugMedicationService, drugMedicationRequest);   
        Long dmId = response.getResponse().getElements().iterator().next().getDrugmedicationIdentifier();
        
        // Clear dosageEnd (since it is calculated in the service during write)
        int rowsAffected = jdbcTemplate.update("UPDATE StructuredDosages SET DosageTimesEndDate=NULL WHERE DosagePID IN (SELECT DosagePID FROM DrugMedications WHERE DrugMedicationIdentifier=?)", dmId);
        assertEquals(rowsAffected, 1);
        
        // Make sure the test works
        timeService.freezeTime(LocalDateTime.now());
        DrugMedicationCompleteVO drugMedication1 = invocator.getDrugMedication(getDrugMedicationService, dmId, LocalDateTime.now());
		DosageTimesVO dtvo = drugMedication1.getDrugMedicationOverview().getDosageVO().getDosageTimesCollection().iterator().next();
		assertFalse(dtvo.getEnd().hasAnything());
		
        // ACT
		updater.update(false, null);
        
        // ASSERT
		
		// Check that the data has been set
		DrugMedicationCompleteVO drugMedication2 = invocator.getDrugMedication(getDrugMedicationService, dmId, LocalDateTime.now());
		dtvo = drugMedication2.getDrugMedicationOverview().getDosageVO().getDosageTimesCollection().iterator().next();
		assertNotNull(dtvo.getEnd());
		assertTrue(dtvo.getEnd().hasAnything());
        assertEquals(dtvo.getEnd().getDate(), LocalDate.now().plusDays(4));
        
        // Check that the drugmedication version has been updated
        assertNotEquals(drugMedication1.getDrugMedicationOverview().getVersion().getNonSequentialVersion(), drugMedication2.getDrugMedicationOverview().getVersion().getNonSequentialVersion());
	}
	
	@Test
	public void testCalcDosageEndForAnyDayShouldDoNothing() throws ParseException {
	
		// ARRANGE
		// Create drugmedication, iteration=0 no dosageenddate
		timeService.freezeTime(LocalDateTime.now().plusDays(-2));
		RequestContext.get().setPersonCPR(TestValueObjectCreator.CPR);
		final CreateDrugmedicationRequest drugMedicationRequest;
        try {
            drugMedicationRequest = testRequestCreator.createDrugMedicationRequest1(TestValueObjectCreator.CPR);
            drugMedicationRequest.setPersonIdentifier(TestValueObjectCreator.CPR);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        
        DosageTimesVO dosageTimeVO = new DosageTimesVO(0, null,
    		new LocalDateOrDateTime(LocalDate.now(), null),
    		new LocalDateOrDateTime(),
    		Arrays.asList(new DosageDayVO(0, null, new DosageTimeVO(BigDecimal.valueOf(1), null, null, DosageTimeType.isMorning, true))));
        
        DosageVO dosageVO = new DosageVO(dosageTimeVO, null, false);
        drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().setDosageVO(dosageVO);
        BeginEndDatesVO beginEndDate = drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().getBeginEndDates();
        drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().setBeginEndDates(new BeginEndDatesVO(beginEndDate.getStartDate(), new LocalDateOrDateTime()));
        
        UpdateResponse<CreateDrugmedicationResponse> response = invocator.createDrugMedication(createDrugMedicationService, drugMedicationRequest);   
        Long dmId = response.getResponse().getElements().iterator().next().getDrugmedicationIdentifier();
        
        // Clear dosageEnd (since it is calculated in the service during write)
        int rowsAffected = jdbcTemplate.update("UPDATE StructuredDosages SET DosageTimesEndDate=NULL WHERE DosagePID IN (SELECT DosagePID FROM DrugMedications WHERE DrugMedicationIdentifier=?)", dmId);
        assertEquals(rowsAffected, 1);
        
        // Make sure the test works
        timeService.freezeTime(LocalDateTime.now());
        DrugMedicationCompleteVO drugMedication1 = invocator.getDrugMedication(getDrugMedicationService, dmId, LocalDateTime.now());
		DosageTimesVO dtvo = drugMedication1.getDrugMedicationOverview().getDosageVO().getDosageTimesCollection().iterator().next();
		assertFalse(dtvo.getEnd().hasAnything());
		
        // ACT
		updater.update(false, null);
        
        // ASSERT
		DrugMedicationCompleteVO drugMedication2 = invocator.getDrugMedication(getDrugMedicationService, dmId, LocalDateTime.now());
		dtvo = drugMedication2.getDrugMedicationOverview().getDosageVO().getDosageTimesCollection().iterator().next();
		assertFalse(dtvo.getEnd().hasAnything());
        
        // Check that the drugmedication version has NOT been updated
        assertEquals(drugMedication1.getDrugMedicationOverview().getVersion().getNonSequentialVersion(), drugMedication2.getDrugMedicationOverview().getVersion().getNonSequentialVersion());

	}
	
	@Test
	public void testCalcDosageEndInTestModeShouldDoNothing() throws ParseException {
	
		// ARRANGE
		// Create drugmedication, iteration=0 no dosageenddate
		timeService.freezeTime(LocalDateTime.now().plusDays(-2));
		RequestContext.get().setPersonCPR(TestValueObjectCreator.CPR);
		final CreateDrugmedicationRequest drugMedicationRequest;
        try {
            drugMedicationRequest = testRequestCreator.createDrugMedicationRequest1(TestValueObjectCreator.CPR);
            drugMedicationRequest.setPersonIdentifier(TestValueObjectCreator.CPR);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        
        DosageTimesVO dosageTimeVO = new DosageTimesVO(0, null,
    		new LocalDateOrDateTime(LocalDate.now(), null),
    		new LocalDateOrDateTime(),
    		Arrays.asList(new DosageDayVO(4, null, new DosageTimeVO(BigDecimal.valueOf(1), null, null, DosageTimeType.isMorning, true))));
        
        DosageVO dosageVO = new DosageVO(dosageTimeVO, null, false);
        drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().setDosageVO(dosageVO);
        BeginEndDatesVO beginEndDate = drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().getBeginEndDates();
        drugMedicationRequest.getDrugmedicationsAndModificators().iterator().next().get().setBeginEndDates(new BeginEndDatesVO(beginEndDate.getStartDate(), new LocalDateOrDateTime()));
        
        UpdateResponse<CreateDrugmedicationResponse> response = invocator.createDrugMedication(createDrugMedicationService, drugMedicationRequest);   
        Long dmId = response.getResponse().getElements().iterator().next().getDrugmedicationIdentifier();
        
        // Clear dosageEnd (since it is calculated in the service during write)
        int rowsAffected = jdbcTemplate.update("UPDATE StructuredDosages SET DosageTimesEndDate=NULL WHERE DosagePID IN (SELECT DosagePID FROM DrugMedications WHERE DrugMedicationIdentifier=?)", dmId);
        assertEquals(rowsAffected, 1);
        
        // Make sure the test works
        timeService.freezeTime(LocalDateTime.now());
        DrugMedicationCompleteVO drugMedication1 = invocator.getDrugMedication(getDrugMedicationService, dmId, LocalDateTime.now());
		DosageTimesVO dtvo = drugMedication1.getDrugMedicationOverview().getDosageVO().getDosageTimesCollection().iterator().next();
		assertFalse(dtvo.getEnd().hasAnything());
		
        // ACT
		updater.update(true, null);
        
        // ASSERT
		DrugMedicationCompleteVO drugMedication2 = invocator.getDrugMedication(getDrugMedicationService, dmId, LocalDateTime.now());
		dtvo = drugMedication2.getDrugMedicationOverview().getDosageVO().getDosageTimesCollection().iterator().next();
		assertFalse(dtvo.getEnd().hasAnything());
        
        // Check that the drugmedication version has NOT been updated
        assertEquals(drugMedication1.getDrugMedicationOverview().getVersion().getNonSequentialVersion(), drugMedication2.getDrugMedicationOverview().getVersion().getNonSequentialVersion());
	}

	@Test
	public void testNonClinicalRepair() {
		//Father CPR: 0505577353
		//Child CPR: 0605088575

		PersonBaseVO.PersonIdentifierVO father = PersonBaseVO.PersonIdentifierVO.fromCPR("0505577353");
		PersonBaseVO.PersonIdentifierVO child = PersonBaseVO.PersonIdentifierVO.fromCPR("0605088575");

		RequestContext.create(SecurityCredentials.ACCESS_TYPE_CONSOLE, "dataupdater_job", null, LocalDateTime.now());
		RequestContext.setValidatedRole(ValidatedRole.roleFor(Role.System, Arrays.asList(Permission.Laegemiddelordination, Permission.SundhedsfagligOpslag)));

		timeService.freezeTime(LocalDateTime.now().plusDays(-2));
		RequestContext.get().setPersonCPR(child);
		final CreateDrugmedicationRequest drugMedicationRequest;
		try {
			drugMedicationRequest = testRequestCreator.createDrugMedicationRequest1(child);
			drugMedicationRequest.setPersonIdentifier(child);
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}

		UpdateResponse<CreateDrugmedicationResponse> response = invocator.createDrugMedication(createDrugMedicationService, drugMedicationRequest);
		Long dmId = response.getResponse().getElements().iterator().next().getDrugmedicationIdentifier();

		timeService.incrementMinutes(60*24);

		ConsentRequest req = new ConsentRequest();
		ModificatorVO fatherModificator = ModificatorVO.makeOther(new ModificatorOtherPersonVO(father, "Mads", null, "Andersen"), Role.ParentAuthority, timeService.currentLocalDateTime());
		req.addDrugMedicationIdentifier(new ModificateValue<>(fatherModificator, dmId), null);
		req.setPersonIdentifier(child);
		req.setMedicineCardIdentifier(MedicineCardIdentifierVO.newPersonIdentifier(child));


		List<Permission> totalPermissions = new ArrayList<>();
		totalPermissions.add(Permission.Privatmarkering);
		RequestContext.setValidatedRole(
				ValidatedRole.roleFor(
						Role.ParentAuthority,
						totalPermissions));
		RequestContext.get().setRequestedRole(Role.ParentAuthority.getSchemaName());

		invocator.removeConsent(consentService, req);

		jdbcTemplate.update("Update MedicineCards SET " +
				"ModifiedDate = ModifiedNonclinicalByDateTime, " +
				"ModifiedNonclinicalByDateTime = NULL, " +
				"HashedModifiedNonclinicalByPID = NULL, " +
				"ModifiedBy = '0505577353' " +
				"WHERE ModifiedNonclinicalByDateTime IS NOT NULL"); //Mimic the error in DB.

		nonClinicalRepair.update(true);

		nonClinicalRepair.update(false);
	}
}
