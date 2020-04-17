package dk.medicinkortet.dataupdater;

import dk.medicinkortet.dao.vo.ModificateValue;
import dk.medicinkortet.dao.vo.UpdateDrugmedicationVO;
import dk.medicinkortet.persistence.mk.datafacade.MedicineCardDataFacade;
import dk.medicinkortet.persistence.mk.datafacade.MedicineCardModificationFacade;
import dk.medicinkortet.persistence.mk.mysql_impl.DAOHolder;
import dk.medicinkortet.persistence.mk.mysql_impl.MyPatientDataFacade;
import dk.medicinkortet.persistence.mk.mysql_impl.dao.DrugMedicationDAO;
import dk.medicinkortet.persistence.stamdata.datafacade.StamdataFacade;
import dk.medicinkortet.services.helpers.DosageStructureHelper;
import dk.medicinkortet.services.vo.DrugMedicationOverview;
import dk.medicinkortet.services.vo.ModificatorVO;
import dk.medicinkortet.services.vo.PersonBaseVO.PersonIdentifierVO;
import dk.medicinkortet.services.vo.dosage.DosageTimesVO;
import dk.medicinkortet.utils.StringUtil;
import dk.medicinkortet.utils.TimeService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Component
public class DosageEnddateUpdater {

	@Autowired
	@Qualifier("drugMedicationDAO")
	DrugMedicationDAO drugMedicationDaoImpl;
	@Autowired
	private DAOHolder daoHolder;
	@Autowired
	private StamdataFacade stamdataFacade;
	@Autowired
	private TimeService timeService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	private static Logger logger = LogManager.getLogger(DosageEnddateUpdater.class);
	private int countSkippedDueToAnyday = 0;
	private int countTotal = 0;
	private int countUpdated = 0;
	private int countExceptions = 0;

	public void update(boolean testMode, String[] cprs) {

		
		String sql = "select ipid.PersonIdentifier, ipid.PersonIdentifierSource, dm.DrugMedicationIdentifier, mc.ValidFrom from DrugMedications dm "
				+ " inner join MedicineCards mc on dm.MedicineCardPID=mc.MedicineCardPID "
				+ " inner join InternalPersonIds ipid on mc.InternalPersonId = ipid.InternalPersonId "
				+ " inner join StructuredDosages sd on sd.DosagePID=dm.DosagePID "
				+ " where dm.HashedWithdrawnByPID is null " 
				+ " and sd.ValidFrom<now() and sd.ValidTo>now() "
				+ " and sd.IterationInterval=0 " 
				+ " and dm.ValidFrom<now() and dm.ValidTo>now() "
				+ " and sd.DosageTimesEndDate is null and sd.DosageTimesEndDateTime is null"
				+ " and ((dm.TreatmentEndDate >= date(now()) OR dm.TreatmentEndDate IS NULL) AND (dm.TreatmentEndDateTime >= now() OR dm.TreatmentEndDateTime IS NULL))";

		if(cprs != null) {
			sql += " and ipid.PersonIdentifier in (" + StringUtil.createSeparatedList(Arrays.asList(cprs), ",", "") + ")";
		}
		
		jdbcTemplate.query(sql, rs -> {

			updateDosageEnd(
					new PersonIdentifierVO(rs.getString("PersonIdentifier"),
							PersonIdentifierVO.Source.fromValue(rs.getString("PersonIdentifierSource"))),
					rs.getLong("DrugMedicationIdentifier"), rs.getTimestamp("ValidFrom").toLocalDateTime(), testMode);

		});

		logger.info("Total: " + countTotal);
		logger.info("Skipped due to AnyDay: " + countSkippedDueToAnyday);
		logger.info("Updated: " + countUpdated);
		logger.info("Exceptions: " + countExceptions);

	}

	public void updateDosageEnd(PersonIdentifierVO personIdentifier, Long drugMedicationIdentifier, LocalDateTime validFrom, boolean testMode) {

		logger.info(personIdentifier.toString() + ": " + drugMedicationIdentifier);

		try {
			MyPatientDataFacade patientDF = new MyPatientDataFacade(daoHolder, personIdentifier, stamdataFacade,
					timeService.currentLocalDateTime());
			MedicineCardDataFacade mc = patientDF.getCurrentMedicineCard();

			Collection<DrugMedicationOverview> dms = mc.getDrugMedications();
			
			DrugMedicationOverview drugMedicationOverview = dms.stream()
					.filter(dm -> dm.getIdentifier().equals(drugMedicationIdentifier)).findFirst().get();

			// Sanity checks - make sure, sql query didn't return anything wrong

			if (!drugMedicationOverview.getDosageVO().hasStructuredDosages()) {
				logger.error("Dosage not structured on drugmedication " + drugMedicationIdentifier);
				return;
			}

			Supplier<Stream<DosageTimesVO>> noniteratedDosageTimes = () -> drugMedicationOverview.getDosageVO()
					.getDosageTimesCollection().stream().filter(dt -> dt.getIterationInterval() == 0
							&& (dt.getEnd() == null || !dt.getEnd().hasAnything()));

			if (noniteratedDosageTimes.get().count() == 0) {
				logger.error("No not-iterated dosage periods without dosageend on drugmedication "
						+ drugMedicationIdentifier);
				return;
			}

			// Skip AnyDay
			if (noniteratedDosageTimes.get()
					.allMatch(dt -> dt.getDosageDayElements().stream().allMatch(day -> day.getDayNumber() == 0))) {
				logger.info("Skipping since all day numbers are 0 for " + drugMedicationIdentifier);
				countSkippedDueToAnyday++;
				return;
			}

			// Fix the missing dates
			boolean updated = DosageStructureHelper.calculateMissingDosageEndDate(drugMedicationOverview.getDosageVO());
			if(updated) {
				logger.info("endDate set: " + drugMedicationOverview.getDosageVO().getDosageTimesCollection().stream().filter(dt -> dt.getIterationInterval() == 0).findFirst().get().getEnd().getAsDate());
				if(!testMode) {

					ModificatorVO existingModificator = drugMedicationOverview.getModified();
	
					String lastName = existingModificator.getOrganisation().getOrganisationName();
					if (lastName.length() > 40) {
						lastName = lastName.substring(0, 40);
					}
					
					ModificatorVO modifiedBy = ModificatorVO.makeSystemModificator(LocalDateTime.now());
					MedicineCardModificationFacade mcModFacade = patientDF.prepareModification(modifiedBy, null, true, "");
	
					// Save in database
					UpdateDrugmedicationVO updateDrugMedication = new UpdateDrugmedicationVO();
					updateDrugMedication.setBeginEndDates(drugMedicationOverview.getBeginEndDates());
					updateDrugMedication.setCreated(drugMedicationOverview.getCreated());
					updateDrugMedication.setDosageVO(drugMedicationOverview.getDosageVO());
					updateDrugMedication.setDrug(drugMedicationOverview.getDrug());
					updateDrugMedication
							.setDrugMedicationCreatedDateTime(drugMedicationOverview.getDrugMedicationCreatedDateTime());
					updateDrugMedication.setFollowUpDates(drugMedicationOverview.getFollowUpDates());
					updateDrugMedication.setHasReimbusementCode(drugMedicationOverview.hasReimbusementCode());
					updateDrugMedication.setIdentifier(drugMedicationOverview.getIdentifier());
					updateDrugMedication.setIndication(drugMedicationOverview.getIndication());
					updateDrugMedication.setIsInvalid(drugMedicationOverview.isInvalid());
					updateDrugMedication.setModified(modifiedBy);
					updateDrugMedication.setModifiedNonclinical(drugMedicationOverview.getModifiedNonclinical());
					updateDrugMedication
							.setModifiedNonclinicalByDateTime(drugMedicationOverview.getModifiedNonclinicalByDateTime());
					updateDrugMedication.setModifiedNonclinicalByPID(drugMedicationOverview.getModifiedNonclinicalByPID());
					updateDrugMedication.setParentIdentifier(drugMedicationOverview.getParentIdentifier());
					updateDrugMedication.setPausedPeriodVO(drugMedicationOverview.getPausedPeriodVO());
					updateDrugMedication.setPriceListVersion(drugMedicationOverview.getPricelistVersion());
					updateDrugMedication.setPrivateDrugMedication(drugMedicationOverview.isPrivateDrugMedication());
					updateDrugMedication.setReported(drugMedicationOverview.getReported());
					updateDrugMedication
							.setReportedNonclinicalByDateTime(drugMedicationOverview.getReportedNonclinicalByDateTime());
					updateDrugMedication.setReportedNonclinicalByPID(drugMedicationOverview.getReportedNonclinicalByPID());
					updateDrugMedication.setRouteOfAdministration(drugMedicationOverview.getRouteOfAdministration());
					updateDrugMedication.setSubstitutionAllowed(drugMedicationOverview.isSubstitutionAllowed());
					updateDrugMedication.setType(drugMedicationOverview.getType());
					updateDrugMedication.setWithdrawn(drugMedicationOverview.getWithdrawn());
	
					ModificateValue<UpdateDrugmedicationVO> modificateUpdateDrugMedication = new ModificateValue<UpdateDrugmedicationVO>(
							updateDrugMedication);
	
					mcModFacade.updateDrugMedication(modificateUpdateDrugMedication,
							updateDrugMedication.getPricelistVersion().getDate(), "DOSAGEENDUPDATER");
					countUpdated++;
				}
			}
		} catch (Exception e) {
			countExceptions++;
			logger.error("Error " + personIdentifier.toString() + " " + drugMedicationIdentifier, e);
		}
		
		countTotal++;
	}
}
