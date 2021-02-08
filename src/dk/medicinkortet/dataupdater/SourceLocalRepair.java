package dk.medicinkortet.dataupdater;

import dk.medicinkortet.advis.AsyncAdvisFacade;
import dk.medicinkortet.authentication.SecurityCredentials;
import dk.medicinkortet.authentication.ValidatedRole;
import dk.medicinkortet.dao.vo.ModificateValue;
import dk.medicinkortet.dao.vo.UpdateDrugmedicationVO;
import dk.medicinkortet.exceptions.PersonDoesNotExistException;
import dk.medicinkortet.persistence.auditlog.datafacade.AuditLoggerFacade;
import dk.medicinkortet.persistence.mk.datafacade.MedicineCardDataFacade;
import dk.medicinkortet.persistence.mk.datafacade.MedicineCardModificationFacade;
import dk.medicinkortet.persistence.mk.mysql_impl.DAOHolder;
import dk.medicinkortet.persistence.mk.mysql_impl.MyPatientDataFacade;
import dk.medicinkortet.persistence.mysql_helpers.CustomJdbcTemplate;
import dk.medicinkortet.persistence.stamdata.datafacade.StamdataFacade;
import dk.medicinkortet.persistence.stamdata.mysql_impl.dao.StamDataDAO;
import dk.medicinkortet.requestcontext.RequestContext;
import dk.medicinkortet.services.vo.*;
import dk.medicinkortet.services.vo.PersonBaseVO.PersonIdentifierVO;
import dk.medicinkortet.services.vo.dosage.DosageTimesVO;
import dk.medicinkortet.utils.TimeService;
import dk.medicinkortet.ws.requestpurpose.RequestPurposeVO;
import dk.medicinkortet.ws.whitelisting.WhitelistingVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static dk.medicinkortet.services.vo.PriceListSourceVO.Source.medicinePrices;

@Component
public class SourceLocalRepair {

	@Autowired private DAOHolder daoHolder;
	@Autowired private StamdataFacade stamdataFacade;
	@Autowired private TimeService timeService;
	@Autowired private CustomJdbcTemplate jdbcTemplate;
	@Autowired protected StamDataDAO stamDataDAO;
	@Autowired @Qualifier("asyncAdvisFacade") private AsyncAdvisFacade advis;
	@Qualifier("auditLogger") @Autowired private AuditLoggerFacade auditLogger;

	private static Logger logger = LogManager.getLogger(SourceLocalRepair.class);

	private int countTotal = 0;
	private int countUpdated = 0;
	private int countFixed = 0;
	private int countExceptions = 0;

	//SQL selects
	private static final String sqlDmBase = "SELECT "
			+ " d.DrugPID, d.DrugId, d.ATCCode, d.DrugFormCode,"
			//Select Person
			+ " ipidDm.PersonIdentifier as personIdentifier,"
			+ " ipidDm.PersonIdentifierSource as personIdentifierSource,"
			//Select PID working on
			+ " dm.DrugMedicationPID as workingPID,"
			//Get DM in case we need to update+advis
			+ " dm.DrugMedicationPID, dm.DrugMedicationIdentifier, dm.Version, dm.ValidTo "
			//Start with problem table
			+ " FROM Drugs d "
			//Is it a DrugMedication?
			+ " INNER JOIN DrugMedications dm on d.DrugPID = dm.DrugPID "
			+ " INNER JOIN MedicineCards mcdm on dm.MedicineCardPID = mcdm.MedicineCardPID "
			+ " INNER JOIN InternalPersonIds ipidDm on mcdm.InternalPersonId = ipidDm.InternalPersonId ";

	private static final String sqlEffBase = "SELECT "
			+ " d.DrugPID, d.DrugId, d.ATCCode, d.DrugFormCode,"
			//Select Person
			+ " ipidEff.PersonIdentifier as personIdentifier,"
			+ " ipidEff.PersonIdentifierSource as personIdentifierSource,"
			//Select PID working on
			+ " eff.EffectuationPID as workingPID"
			//Start with problem table
			+ " FROM Drugs d "
			//Is is an Effectuation?
			+ " INNER JOIN Effectuations eff on d.DrugPID = eff.DrugPID "
			+ " INNER JOIN DrugMedications effDm on eff.DrugMedicationPID = effDm.DrugMedicationPID "
			+ " INNER JOIN MedicineCards mcEff on effDm.MedicineCardPID = mcEff.MedicineCardPID "
			+ " INNER JOIN InternalPersonIds ipidEff on mcEff.InternalPersonId = ipidEff.InternalPersonId";

	private static final String sqlPackBase = "SELECT "
			+ " d.DrugPID, d.DrugId, d.ATCCode, d.DrugFormCode,"
			//Select Person
			+ " ipidEffPack.PersonIdentifier as personIdentifier,"
			+ " ipidEffPack.PersonIdentifierSource as personIdentifierSource,"
			//Select PID working on
			+ " pack.PackagePID as workingPID"
			//Start with problem table
			+ " FROM Drugs d "
			//Is it a Package?
			+ " INNER JOIN Packages pack on d.DrugPID = pack.DrugPID "
			+ " INNER JOIN Effectuations effPack on pack.PackagePID = effPack.PackagePID "
			+ " INNER JOIN DrugMedications effPackDm on effPack.DrugMedicationPID = effPackDm.DrugMedicationPID "
			+ " INNER JOIN MedicineCards mcEffPack on effPackDm.MedicineCardPID = mcEffPack.MedicineCardPID "
			+ " INNER JOIN InternalPersonIds ipidEffPack on mcEffPack.InternalPersonId = ipidEffPack.InternalPersonId ";

	private static final String sqlPackagedBase = "SELECT "
			+ " d.DrugPID, d.DrugId, d.ATCCode, d.DrugFormCode,"
			//Select Person
			+ " ipidDD.PersonIdentifier as personIdentifier,"
			+ " ipidDD.PersonIdentifierSource as personIdentifierSource,"
			//Select PID working on
			+ " packDrug.PackagedDrugPID as workingPID"
			//Start with problem table
			+ " FROM Drugs d "
			//Is is a PackagedDrug? (DD)
			+ " INNER JOIN PackagedDrug packDrug on d.DrugPID = packDrug.Drug "
			+ " INNER JOIN DoseDispensingPeriods ddPeriod on packDrug.DoseDispensingPeriodIdentifier = ddPeriod.DoseDispensingPeriodIdentifier AND ddPeriod.ValidTo > now()"
			+ " INNER JOIN DoseDispensingCards ddCard on ddPeriod.DoseDispensingCardIdentifier = ddCard.DoseDispensingCardIdentifier"
			+" AND ddCard.ValidFrom <= ddPeriod.ValidFrom AND ddCard.ValidTo > ddPeriod.ValidFrom"
			+ " INNER JOIN InternalPersonIds ipidDD on ddCard.InternalPersonId = ipidDD.InternalPersonId ";

	private static final String sqlPlannedBase = "SELECT "
			+ " d.DrugPID, d.DrugId, d.ATCCode, d.DrugFormCode,"
			//Select Person
			+ " ipidDD2.PersonIdentifier as personIdentifier,"
			+ " ipidDD2.PersonIdentifierSource as personIdentifierSource,"
			//Select PID working on
			+ " plannedDrug.PlannedDispensingPID as workingPID"
			//Start with problem table
			+ " FROM Drugs d "
			//Is it a PlannedDispensing (substituded drug)?
			+ " INNER JOIN PlannedDispensings plannedDrug on d.DrugPID = plannedDrug.SubstitutedDrugPID "
			+ " INNER JOIN DoseDispensingCards ddCardPlanned on plannedDrug.DoseDispensingCardIdentifier = ddCardPlanned.DoseDispensingCardIdentifier"
			+ " AND ddCardPlanned.ValidFrom <= plannedDrug.ValidFrom AND ddCardPlanned.ValidTo > plannedDrug.ValidFrom"
			+ " INNER JOIN InternalPersonIds ipidDD2 on ddCardPlanned.InternalPersonId = ipidDD2.InternalPersonId";

	public void runRepair(boolean testMode) {
		logger.info("Starting repair, testmode: " + testMode + " at time: " + timeService.currentLocalDateTime() + " : " + LocalDateTime.now());
		countTotal = 0;
		countUpdated = 0;
		countFixed = 0;
		countExceptions = 0;

		List<LocalUpdate> itemsToFix = new ArrayList<>();

		setupTempTables();

		logger.info("1. Checking DrugIds for incorrect source local");
		for (Workingtable value : Workingtable.values()) {
			logger.info("Checking Wrong DrugIdSource linked to table: " + value.name());
			refreshTempTable(true, false, false, false);
			fetchWrongDrugId(itemsToFix, value);
			logger.info("Finished finding wrong DrugIdSource linked to table: " + value.name());
		}
		logger.info("1.1 currently " + itemsToFix.size() + " items to repair");

		logger.info("2. Checking ATC for incorrect source local");
		for (Workingtable value : Workingtable.values()) {
			logger.info("Checking Wrong AtcCodeSource linked to table: " + value.name());
			refreshTempTable(false, true, false, false);
			fetchWrongATC(itemsToFix, value);
			logger.info("Finished finding wrong AtcCodeSource linked to table: " + value.name());
		}
		logger.info("2.1 currently " + itemsToFix.size() + " items to repair");

		logger.info("3. Checking FormCode for incorrect source local");
		//Find Drugs with wrong source local on FormCode
		for (Workingtable value : Workingtable.values()) {
			logger.info("Checking Wrong FormCodeSource linked to table: " + value.name());
			refreshTempTable(false, false, true, false);
			fetchWrongFormCode(itemsToFix, value);
			logger.info("Finished finding wrong FormCodeSource linked to table: " + value.name());
		}
		logger.info("3.1 currently " + itemsToFix.size() + " items to repair");

		//Find DrugMedications with wrong source local on Indication
		String sqlIndication = "select ipid.PersonIdentifier, ipid.PersonIdentifierSource,"
				+" dm.DrugMedicationPID, dm.DrugMedicationIdentifier, dm.Version, dm.ValidTo, dm.IndicationCode,"
				+ " d.DrugPID"
				+ " FROM DrugMedications dm "
				+ " INNER JOIN MedicineCards mc on dm.MedicineCardPID = mc.MedicineCardPID "
				+ " INNER JOIN InternalPersonIds ipid on mc.InternalPersonId = ipid.InternalPersonId "
				+ " INNER JOIN Drugs d on dm.DrugPID = d.DrugPID "
				+ " INNER JOIN tempIndication t ON t.IndikationKode = dm.IndicationCode"
				+ " WHERE dm.IndicationCode IS NOT NULL AND dm.IndicationCodeSource = 'Local'";

		logger.info("4. Checking Indication for incorrect source local");

		try {
			refreshTempTable(false, false, false, true);
			jdbcTemplate.query(sqlIndication,
					rs -> {
						PersonIdentifierVO person = new PersonIdentifierVO(rs.getString("PersonIdentifier"), PersonIdentifierVO.Source.fromValue(rs.getString("PersonIdentifierSource")));
						long dmPID = rs.getLong("dm.DrugMedicationPID");
						long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
						long dmVersion = rs.getLong("dm.Version");
						LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
						long drugPID = rs.getLong("d.DrugPID");
						Integer indication = rs.getInt("dm.IndicationCode");
						LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDrugPID() == drugPID).findFirst().orElse(null);
						if (currentUpdate != null) {
							currentUpdate.setIndicationCode(indication);
						} else {
							LocalUpdate update = new LocalUpdate();
							update.setPerson(person);
							update.setDmPID(dmPID);
							update.setDmIdentifier(dmIdentifier);
							update.setDmVersion(dmVersion);
							update.setDmValidTo(dmValidTo);
							update.setDrugPID(drugPID);
							update.setIndicationCode(indication);
							update.setWorkingPID(dmPID);
							update.setDrugLinkedToTable("DrugMedications");
							itemsToFix.add(update);
						}
					});

		} catch (Exception e) {
			logger.error("Failed query for Indication", e);
		}

		logger.info("4.1 currently " + itemsToFix.size() + " items to repair");

		logger.info("SourceLocalRepair, found a total of " + itemsToFix.size() + " items to repair. time: " + timeService.currentLocalDateTime() + " : " + LocalDateTime.now());

		List<PersonIdentifierVO> personsUpdated = new ArrayList<>();

		updateSourceLocal(itemsToFix, personsUpdated, testMode);

		personsUpdated = personsUpdated.stream().distinct().collect(Collectors.toList());

		if (RequestContext.isSet() && !testMode) {
			sendAuditlogForPatients(personsUpdated);
		}

		logger.info("Total: " + countTotal);
		logger.info("Updated: " + countUpdated);
		logger.info("Fixed: " + countFixed);
		logger.info("Exceptions: " + countExceptions);
		logger.info("Program terminating at: " + timeService.currentLocalDateTime() + " : " + LocalDateTime.now());
	}

	private void refreshTempTable(boolean drugId, boolean atc, boolean form, boolean indication) {
		if (drugId) {
			jdbcTemplate.update("DROP TEMPORARY TABLE IF EXISTS tempDrugId");
			try {
				logger.info("Sleeping 3 second to ensure temp tables show in next query");
				Thread.sleep(3000);
			} catch (Exception e) {
				logger.error("Failed to sleep 3, temp table might not create properly.");
			}
			jdbcTemplate.update("CREATE TEMPORARY TABLE IF NOT EXISTS tempDrugId (Primary key (DrugId)) SELECT DISTINCT(DrugId) FROM " + jdbcTemplate.getSdmDatabase() + ".Laegemiddel");
		}
		if (atc) {
			jdbcTemplate.update("DROP TEMPORARY TABLE IF EXISTS tempAtc");
			try {
				logger.info("Sleeping 3 second to ensure temp tables show in next query");
				Thread.sleep(3000);
			} catch (Exception e) {
				logger.error("Failed to sleep 3, temp table might not create properly.");
			}
			jdbcTemplate.update("CREATE TEMPORARY TABLE IF NOT EXISTS tempAtc (Primary key (ATC)) SELECT DISTINCT(ATC) FROM " + jdbcTemplate.getSdmDatabase() + ".ATC");
		}
		if (form) {
			jdbcTemplate.update("DROP TEMPORARY TABLE IF EXISTS tempFormCode");
			try {
				logger.info("Sleeping 3 second to ensure temp tables show in next query");
				Thread.sleep(3000);
			} catch (Exception e) {
				logger.error("Failed to sleep 3, temp table might not create properly.");
			}
			jdbcTemplate.update("CREATE TEMPORARY TABLE IF NOT EXISTS tempFormCode (Primary key (Kode)) SELECT DISTINCT(Kode) FROM " + jdbcTemplate.getSdmDatabase() + ".Formbetegnelse");
		}
		if (indication) {
			jdbcTemplate.update("DROP TEMPORARY TABLE IF EXISTS tempIndication");
			try {
				logger.info("Sleeping 3 second to ensure temp tables show in next query");
				Thread.sleep(3000);
			} catch (Exception e) {
				logger.error("Failed to sleep 3, temp table might not create properly.");
			}
			jdbcTemplate.update("CREATE TEMPORARY TABLE IF NOT EXISTS tempIndication (Primary key (IndikationKode)) SELECT DISTINCT(IndikationKode) FROM " + jdbcTemplate.getSdmDatabase() + ".Indikation");
		}
		try {
			logger.info("Sleeping 5 seconds to ensure temp tables show in next query");
			Thread.sleep(5000);
		} catch (Exception e) {
			logger.error("Failed to sleep 5, temp table might not show.");
		}
	}

	private void setupTempTables() {
		logger.info("0.0 Setting up temporary tables, removing old");
		int update = jdbcTemplate.update("DROP TEMPORARY TABLE IF EXISTS tempDrugId");
		if (update > 0) {
			logger.info("Removed " + update + " rows from tempDrugId");
		}
		update = jdbcTemplate.update("DROP TEMPORARY TABLE IF EXISTS tempAtc");
		if (update > 0) {
			logger.info("Removed " + update + " rows from tempAtc");
		}
		update = jdbcTemplate.update("DROP TEMPORARY TABLE IF EXISTS tempFormCode");
		if (update > 0) {
			logger.info("Removed " + update + " rows from tempFormCode");
		}
		update = jdbcTemplate.update("DROP TEMPORARY TABLE IF EXISTS tempIndication");
		if (update > 0) {
			logger.info("Removed " + update + " rows from tempIndication");
		}
		logger.info("0.0 Setting up temporary tables, making new");
		try {
			logger.info("0.0.1 Sleeping 10 seconds to complete remove query before building new tables");
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		//Create temporary tables to join on, which only contains the distinct values we need to check exists in SDM
		jdbcTemplate.update("CREATE TEMPORARY TABLE IF NOT EXISTS tempDrugId (Primary key (DrugId)) SELECT DISTINCT(DrugId) FROM " + jdbcTemplate.getSdmDatabase() + ".Laegemiddel");
		logger.info("0.1 Setup temp table for DrugId Completed");
		jdbcTemplate.update("CREATE TEMPORARY TABLE IF NOT EXISTS tempAtc (Primary key (ATC)) SELECT DISTINCT(ATC) FROM " + jdbcTemplate.getSdmDatabase() + ".ATC");
		logger.info("0.2 Setup temp table for ATC Completed");
		jdbcTemplate.update("CREATE TEMPORARY TABLE IF NOT EXISTS tempFormCode (Primary key (Kode)) SELECT DISTINCT(Kode) FROM " + jdbcTemplate.getSdmDatabase() + ".Formbetegnelse");
		logger.info("0.3 Setup temp table for FormCode Completed");
		jdbcTemplate.update("CREATE TEMPORARY TABLE IF NOT EXISTS tempIndication (Primary key (IndikationKode)) SELECT DISTINCT(IndikationKode) FROM " + jdbcTemplate.getSdmDatabase() + ".Indikation");
		logger.info("0.4 Setup temp table for Indication Completed");
	}

	private void fetchWrongFormCode(List<LocalUpdate> itemsToFix, Workingtable value) {
		String joinTemp = " INNER JOIN tempFormCode t ON t.Kode = d.DrugFormCode";
		String where = " WHERE d.DrugFormCode IS NOT NULL AND d.DrugFormCodeSource = 'Local'";
		String sqlFormCode;

		switch (value) {
			case DrugMedication:
				sqlFormCode = sqlDmBase + joinTemp + where;
				break;
			case Effectuation:
				sqlFormCode = sqlEffBase + joinTemp + where;
				break;
			case Package:
				sqlFormCode = sqlPackBase + joinTemp + where;
				break;
			case PlannedDispensing:
				sqlFormCode = sqlPlannedBase + joinTemp + where;
				break;
			case PackagedDrug:
				sqlFormCode = sqlPackagedBase + joinTemp + where;
				break;
			default:
				//Shouldn't be possible to get to here
				logger.error("Unsupported value for WorkingTable: " + value.name());
				return;
		}

		try {
			jdbcTemplate.query(sqlFormCode,
					rs -> {
						long drugPID = rs.getLong("d.DrugPID");
						String formCode = rs.getString("d.DrugFormCode");

						LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDrugPID() == drugPID).findFirst().orElse(null);
						if (currentUpdate != null) {
							currentUpdate.setFormCode(formCode);
						} else {
							LocalUpdate update = new LocalUpdate();

							switch (value) {
								case DrugMedication:
									long dmPID = rs.getLong("dm.DrugMedicationPID");
									long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
									long dmVersion = rs.getLong("dm.Version");
									LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
									update.setDmPID(dmPID);
									update.setDmIdentifier(dmIdentifier);
									update.setDmVersion(dmVersion);
									update.setDmValidTo(dmValidTo);
									update.setDrugLinkedToTable("DrugMedications");
									break;
								case Effectuation:
									update.setDrugLinkedToTable("Effectuations");
									break;
								case Package:
									update.setDrugLinkedToTable("Packages");
									break;
								case PlannedDispensing:
									update.setDrugLinkedToTable("PlannedDispensings");
									break;
								case PackagedDrug:
									update.setDrugLinkedToTable("PackagedDrug");
									break;
								default:
									return;
							}

							String personIdentifier = rs.getString("personIdentifier");
							String source = rs.getString("personIdentifierSource");

							if (personIdentifier == null || source == null) {
								logger.info("Person not found!, PID: " + drugPID + " : " + value.name());
								return;
							}

							PersonIdentifierVO person = new PersonIdentifierVO(personIdentifier,
									PersonIdentifierVO.Source.fromValue(source));

							long workingPID = rs.getLong("workingPID");
							update.setPerson(person);
							update.setDrugPID(drugPID);
							update.setFormCode(formCode);
							update.setWorkingPID(workingPID);

							itemsToFix.add(update);
						}
					});
		} catch (Exception e) {
			logger.error("Failed query for FormCode linked to table: " + value.name(), e);
		}
	}

	private void fetchWrongATC(List<LocalUpdate> itemsToFix, Workingtable value) {
		String sqlATC;
		String joinTemp = " INNER JOIN tempAtc t ON t.ATC = d.ATCCode";
		String where = " WHERE d.ATCCode IS NOT NULL AND d.ATCCodeSource = 'Local'";
		switch (value) {
			case DrugMedication:
				sqlATC = sqlDmBase + joinTemp + where;
				break;
			case Effectuation:
				sqlATC = sqlEffBase + joinTemp + where;
				break;
			case Package:
				sqlATC = sqlPackBase + joinTemp + where;
				break;
			case PlannedDispensing:
				sqlATC = sqlPlannedBase + joinTemp + where;
				break;
			case PackagedDrug:
				sqlATC = sqlPackagedBase + joinTemp + where;
				break;
			default:
				logger.error("Unsupported value for WorkingTable: " + value.name());
				return;
		}

		try {
			jdbcTemplate.query(sqlATC,
					rs -> {
						long drugPID = rs.getLong("d.DrugPID");
						String atc = rs.getString("d.ATCCode");

						LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDrugPID() == drugPID).findFirst().orElse(null);
						if (currentUpdate != null) {
							currentUpdate.setAtcCode(atc);
						} else {
							LocalUpdate update = new LocalUpdate();

							switch (value) {
								case DrugMedication:
									long dmPID = rs.getLong("dm.DrugMedicationPID");
									long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
									long dmVersion = rs.getLong("dm.Version");
									LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
									update.setDmPID(dmPID);
									update.setDmIdentifier(dmIdentifier);
									update.setDmVersion(dmVersion);
									update.setDmValidTo(dmValidTo);
									update.setDrugLinkedToTable("DrugMedications");
									break;
								case Effectuation:
									update.setDrugLinkedToTable("Effectuations");
									break;
								case Package:
									update.setDrugLinkedToTable("Packages");
									break;
								case PlannedDispensing:
									update.setDrugLinkedToTable("PlannedDispensings");
									break;
								case PackagedDrug:
									update.setDrugLinkedToTable("PackagedDrug");
									break;
								default:
									return;
							}

							String personIdentifier = rs.getString("personIdentifier");
							String source = rs.getString("personIdentifierSource");
							logger.info("Found work for person: " + personIdentifier + ":" + source);

							if (personIdentifier == null || source == null) {
								logger.info("Person not found!, PID: " + drugPID + " : " + value.name());
								return;
							}

							PersonIdentifierVO person = new PersonIdentifierVO(personIdentifier,
									PersonIdentifierVO.Source.fromValue(source));

							long workingPID = rs.getLong("workingPID");
							update.setPerson(person);
							update.setDrugPID(drugPID);
							update.setAtcCode(atc);
							update.setWorkingPID(workingPID);

							itemsToFix.add(update);
						}
					});
		} catch (Exception e) {
			logger.error("Failed query for FormCode linked to table: " + value.name(), e);
		}
	}

	private void fetchWrongDrugId(List<LocalUpdate> itemsToFix, Workingtable value) {
		String sqlDrugId;
		String joinTemp = " INNER JOIN tempDrugId t ON t.DrugId = d.DrugId";
		String where = " WHERE d.DrugId IS NOT NULL AND d.DrugIdSource = 'Local'";
		switch (value) {
			case DrugMedication:
				sqlDrugId = sqlDmBase + joinTemp + where;
				break;
			case Effectuation:
				sqlDrugId = sqlEffBase + joinTemp + where;
				break;
			case Package:
				sqlDrugId = sqlPackBase + joinTemp + where;
				break;
			case PlannedDispensing:
				sqlDrugId = sqlPlannedBase + joinTemp + where;
				break;
			case PackagedDrug:
				sqlDrugId = sqlPackagedBase + joinTemp + where;
				break;
			default:
				logger.error("Unsupported value for WorkingTable: " + value.name());
				return;
		}

		try {
			jdbcTemplate.query(sqlDrugId,
					rs -> {
						long drugPID = rs.getLong("d.DrugPID");

						LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDrugPID() == drugPID).findFirst().orElse(null);
						if (currentUpdate != null) {
							logger.warn("FOUND Duplicate: " + currentUpdate.getDrugPID());
						} else {
							LocalUpdate update = new LocalUpdate();

							switch (value) {
								case DrugMedication:
									long dmPID = rs.getLong("dm.DrugMedicationPID");
									long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
									long dmVersion = rs.getLong("dm.Version");
									LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
									update.setDmPID(dmPID);
									update.setDmIdentifier(dmIdentifier);
									update.setDmVersion(dmVersion);
									update.setDmValidTo(dmValidTo);
									update.setDrugLinkedToTable("DrugMedications");
									break;
								case Effectuation:
									update.setDrugLinkedToTable("Effectuations");
									break;
								case Package:
									update.setDrugLinkedToTable("Packages");
									break;
								case PlannedDispensing:
									update.setDrugLinkedToTable("PlannedDispensings");
									break;
								case PackagedDrug:
									update.setDrugLinkedToTable("PackagedDrug");
									break;
								default:
									return;
							}

							String personIdentifier = rs.getString("personIdentifier");
							String source = rs.getString("personIdentifierSource");
							logger.info("Found work for person: " + personIdentifier + ":" + source);

							if (personIdentifier == null || source == null) {
								logger.info("Person not found!, PID: " + drugPID + " : " + value.name());
								return;
							}

							PersonIdentifierVO person = new PersonIdentifierVO(personIdentifier,
									PersonIdentifierVO.Source.fromValue(source));

							long drugId = rs.getLong("d.DrugId");
							long workingPID = rs.getLong("workingPID");
							update.setPerson(person);
							update.setDrugPID(drugPID);
							update.setDrugId(drugId);
							update.setWorkingPID(workingPID);

							itemsToFix.add(update);
						}
					});
		} catch (Exception e) {
			logger.error("Failed query for FormCode linked to table: " + value.name(), e);
		}
	}

	public void updateSourceLocal(List<LocalUpdate> itemsToFix, List<PersonIdentifierVO> personsUpdated, boolean testMode) {

		PersonIdentifierVO lastPerson = null;
		for (LocalUpdate item : itemsToFix) {

			boolean isLatestDmVersion = item.getDmValidTo() != null && item.getDmIdentifier() != null && item.getDmValidTo().isAfter(timeService.currentLocalDateTime());

			logger.info("Person: " +item.getPerson().toString() +
					" Work item: "  +
					" DrugPID: " + item.getDrugPID() +
					" Latest DM Version? " + isLatestDmVersion +
					" LinkedTo: " + item.getDrugLinkedToTable() + " with PID: " + item.getWorkingPID() + " " +
					item.getFixingString());

			if (testMode && isLatestDmVersion) {
				try {
					jdbcTemplate.query("SELECT o.* FROM PatientRelations p" +
							" INNER JOIN Organisations o ON p.OrganisationPID = o.OrganisationPID" +
							" INNER JOIN InternalPersonIds id ON p.InternalPersonId = id.InternalPersonId" +
							" WHERE p.Type = 'VISITERET_TIL_MEDICINADMINISTRATION' AND p.ValidTo > now() AND id.PersonIdentifier = ? AND p.HashedRemovedByPID IS NULL", rs -> {
						logger.warn("Patient ved medicin-administration ved: " +
								rs.getString("o.OrganisationName") +
								" ID: " + rs.getString("o.Identifier") + " Type: " + rs.getString("o.Type"));
					}, item.getPerson().getIdentifier());
				} catch (Exception e) {
					logger.error("Failed to check for patient relations: ", e);
				}
			}


			if (!testMode) {
				try {
					if (item.getDmVersion() != null &&
							item.getDmValidTo() != null &&
							item.getDmIdentifier() != null &&
							item.getDmValidTo().isAfter(timeService.currentLocalDateTime())) {
						logger.info("Fixing latest version of DM: " + item.getDmIdentifier() + ":" + item.getDmVersion() +
								" creating new version and sending advis.");

						if (lastPerson != null && lastPerson.equals(item.getPerson())){
							try {
								logger.info("Sleeping 1 second since updating same person as previous item.");
								Thread.sleep(1000);
							} catch (Exception e) {
								logger.error("Failed to sleep to avoid same time updates");
							}
						}
						lastPerson = item.getPerson();

						MyPatientDataFacade patientDF = null;
						try {
							patientDF = new MyPatientDataFacade(daoHolder, item.getPerson(), stamdataFacade, timeService.currentLocalDateTime());
						} catch (PersonDoesNotExistException e) {
							Thread.sleep(1000);
							patientDF = new MyPatientDataFacade(daoHolder, item.getPerson(), stamdataFacade, LocalDateTime.now());
						}
						ModificatorVO modifiedBy = makeDataUpdaterModificator(LocalDateTime.now());

						MedicineCardDataFacade mc = patientDF.getCurrentMedicineCard();

						DrugMedicationOverview drugMedicationOverview = patientDF.getDrugMedicationFacade()
								.getDrugMedicationByIdentifier(item.getDmIdentifier(),
										null, timeService.currentLocalDateTime(), timeService.currentLocalDateTime(),
										true);

						//Structured dosages arn't collected
						Collection<DosageTimesVOWithPid> dosages = patientDF.getDrugMedicationFacade().getDosages(drugMedicationOverview, drugMedicationOverview.getVersion().getVersionNumber());
						if (dosages != null) {
							drugMedicationOverview.getDosageVO().setDosageTimesCollection(new ArrayList<DosageTimesVO>(dosages));
						}

						if (drugMedicationOverview == null) {
							throw new IllegalStateException("DrugMedication overview was null!");
						}

						MedicineCardModificationFacade mcModFacade = patientDF.prepareModification(modifiedBy, null, true, "");

						//Do fixes
						updateSourceLocalOnDrug(drugMedicationOverview, drugMedicationOverview.getDrug(), item);
						updateSourceLocalOnIndication(drugMedicationOverview.getIndication(), item);

						//Update DM, create new version.
						UpdateDrugmedicationVO updateDrugMedication = new UpdateDrugmedicationVO();
						updateDrugMedication.setBeginEndDates(drugMedicationOverview.getBeginEndDates());
						updateDrugMedication.setCreated(drugMedicationOverview.getCreated());
						updateDrugMedication.setDosageVO(drugMedicationOverview.getDosageVO());
						updateDrugMedication.setDrug(drugMedicationOverview.getDrug());
						updateDrugMedication.setDrugMedicationCreatedDateTime(drugMedicationOverview.getDrugMedicationCreatedDateTime());
						updateDrugMedication.setFollowUpDates(drugMedicationOverview.getFollowUpDates());
						updateDrugMedication.setHasReimbusementCode(drugMedicationOverview.hasReimbusementCode());
						updateDrugMedication.setIdentifier(drugMedicationOverview.getIdentifier());
						updateDrugMedication.setIndication(drugMedicationOverview.getIndication());
						updateDrugMedication.setIsInvalid(drugMedicationOverview.isInvalid());
						updateDrugMedication.setModified(modifiedBy);
						updateDrugMedication.setModifiedNonclinical(drugMedicationOverview.getModifiedNonclinical());
						updateDrugMedication.setModifiedNonclinicalByDateTime(drugMedicationOverview.getModifiedNonclinicalByDateTime());
						updateDrugMedication.setModifiedNonclinicalByPID(drugMedicationOverview.getModifiedNonclinicalByPID());
						updateDrugMedication.setParentIdentifier(drugMedicationOverview.getParentIdentifier());
						updateDrugMedication.setPausedPeriodVO(drugMedicationOverview.getPausedPeriodVO());
						updateDrugMedication.setPriceListVersion(drugMedicationOverview.getPricelistVersion());
						updateDrugMedication.setPrivateDrugMedication(drugMedicationOverview.isPrivateDrugMedication());
						updateDrugMedication.setReported(drugMedicationOverview.getReported());
						updateDrugMedication.setReportedNonclinicalByDateTime(drugMedicationOverview.getReportedNonclinicalByDateTime());
						updateDrugMedication.setReportedNonclinicalByPID(drugMedicationOverview.getReportedNonclinicalByPID());
						updateDrugMedication.setRouteOfAdministration(drugMedicationOverview.getRouteOfAdministration());
						updateDrugMedication.setSubstitutionAllowed(drugMedicationOverview.isSubstitutionAllowed());
						updateDrugMedication.setType(drugMedicationOverview.getType());
						updateDrugMedication.setWithdrawn(drugMedicationOverview.getWithdrawn());

						ModificateValue<UpdateDrugmedicationVO> modificateUpdateDrugMedication = new ModificateValue<>(
								updateDrugMedication);

						DrugMedicationIdAndVersion newVersion = mcModFacade.updateDrugMedication(modificateUpdateDrugMedication,
								updateDrugMedication.getPricelistVersion().getDate(), "SOURCE-LOCAL-REPAIR");

						advis.drugMedicationUpdated(item.getPerson(), mc.getNonSequentialVersion(),
								newVersion.getIdentifier(), newVersion.getVersion().getNonSequentialVersion());

						logger.info("Created new version of DM: " + item.getDmIdentifier() +
								". New Version: " + newVersion.getVersion().getNonSequentialVersion() +
								", Advis sent");

						personsUpdated.add(item.getPerson());

						countUpdated++;
						countTotal++;
					}
				} catch (Exception e) {
					countExceptions++;
					countTotal++;
					logger.error("Error creating new DM version, aborting update of current version! - " +
							item.getPerson().toString() + " : " + item.getDrugPID() +
							" : " + item.getDmIdentifier() + " : " + item.getFixingString(), e);
					continue; //Don't try and repair the current version, that'll cause problems
				}

				//Fix current version!
				try {
					if (item.getDrugId() != null || item.getFormCode() != null || item.getAtcCode() != null) {
						fixSourceLocalOnDrug(item);
					}

					if (item.getIndicationCode() != null) {
						fixSourceLocalOnDrugMedication(item);
					}

					personsUpdated.add(item.getPerson());

					countFixed++;
					countTotal++;
				} catch (Exception e) {
					countExceptions++;
					countTotal++;
					logger.error("Error repairing older version! - " +
							item.getPerson().toString() + " Drug: " + item.getDrugPID() +
							" LinkedTo: " + item.getDrugLinkedToTable() + " WithPID: " + item.getWorkingPID() + " " + item.getFixingString(), e);
				}
			}
		}
	}

	private void fixSourceLocalOnDrugMedication(LocalUpdate item) {
		String updateSql = "UPDATE DrugMedications SET IndicationCodeSource = 'Medicinpriser', IndicationCodeDate = " +
				"(SELECT MAX(ValidFrom) FROM " + jdbcTemplate.getSdmDatabase() + ".Indikation WHERE IndikationKode = ?)";

		updateSql += " WHERE DrugMedicationPID = ?";

		jdbcTemplate.update(updateSql, item.getIndicationCode(), item.getDmPID());
		logger.info("UPDATED DrugMedication with identifier: " + item.getDmIdentifier() + " and PID: " + item.getDmPID());
	}

	private void fixSourceLocalOnDrug(LocalUpdate item) {
		String updateSql = "UPDATE Drugs SET ";
		boolean b = false;
		if (item.getDrugId() != null) {
			updateSql += "DrugIdSource = 'Medicinpriser'," +
					" DrugIdDate = (SELECT MAX(ValidFrom) FROM " + jdbcTemplate.getSdmDatabase() +
					".Laegemiddel WHERE DrugID = '" + item.getDrugId() + "')";
			b = true;
		}
		if (item.getAtcCode() != null) {
			updateSql += (b ? ", " : " ") + "ATCCodeSource = 'Medicinpriser'," +
					" ATCCodeSourceDate = (SELECT MAX(ValidFrom) FROM " + jdbcTemplate.getSdmDatabase() +
					".ATC WHERE ATC = '" + item.getAtcCode() + "')";
			b = true;
		}
		if (item.getFormCode() != null) {
			updateSql += (b ? ", " : " ") + "DrugFormCodeSource = 'Medicinpriser'," +
					" DrugFormCodeSourceDate = (SELECT MAX(ValidFrom) FROM " + jdbcTemplate.getSdmDatabase() +
					".Formbetegnelse WHERE Kode = '" + item.getFormCode() + "')";
		}

		updateSql += " WHERE DrugPID = ?";

		jdbcTemplate.update(updateSql, item.getDrugPID());
		logger.info("UPDATED DrugPID: " + item.getDrugPID());
	}

	private void updateSourceLocalOnIndication(FreeTextAndCodeVO indication, LocalUpdate item) {
		if (item.getIndicationCode() != null) {
			TextAndCodeVO indikation = stamdataFacade.getIndikation(timeService.currentLocalDateTime(), item.getIndicationCode().toString());
			if (indikation != null) {
				indication.setCode(indikation.getCode());
				indication.setText(indikation.getText());
				indication.setIndicationSource(new PriceListSourceVO(indikation.getValidFrom(), medicinePrices));
			} else {
				List<TextAndCodeVO> indikationList = stamDataDAO.getIndikationList(item.getIndicationCode().toString());
				if (indikationList != null && indikationList.size() > 0) {
					TextAndCodeVO vo = indikationList.stream().max(Comparator.comparing(TextAndCodeVO::getValidFrom)).orElse(null);
					indication.setCode(vo.getCode());
					indication.setText(vo.getText());
					indication.setIndicationSource(new PriceListSourceVO(vo.getValidFrom(), medicinePrices));
				}
			}
		}
	}

	private void updateSourceLocalOnDrug(DrugMedicationOverview drugMedicationOverview, DrugVO drug, LocalUpdate item) {
		if (item.getDrugId() != null) {
			//Fix drugId, ATC and Form
			StamdataFacade.StamLaegemiddelVO stamDrug = stamdataFacade.getLaegemiddel(timeService.currentLocalDateTime(), item.getDrugId());
			if (stamDrug != null) {
				drug.setIdentifierSource(new DrugIdentifierSourceVO(stamDrug.validFrom, "Medicinpriser"));
				TextAndCodeVO atc = new TextAndCodeVO(stamDrug.atcCode, stamDrug.atcText);
				atc.setSource(new PriceListSourceVO(stamDrug.validFrom, medicinePrices));
				drug.setAtc(atc);
				TextAndCodeVO form = new TextAndCodeVO(stamDrug.formCode, stamDrug.formText);
				form.setSource(new PriceListSourceVO(stamDrug.validFrom, medicinePrices));
				drug.setForm(form);
			} else {
				logger.warn("Unable to fix Drug for DrugPID: " + item.getDrugPID() + " couldn't find stamDrug for drugId: " + item.getDrugId());
			}
			drugMedicationOverview.setDrug(drug);
			return;
		}
		//Not fixing drug from DrugId, fix the other units.
		//Fix ATC if needed
		if (item.getAtcCode() != null) {
			TextAndCodeVO atc = stamdataFacade.getATC(timeService.currentLocalDateTime(), item.getAtcCode());
			if (atc != null) {
				atc.setSource(new PriceListSourceVO(atc.getValidFrom(), medicinePrices));
				drug.setAtc(atc);
			} else {
				List<TextAndCodeVO> atcList = stamDataDAO.getATCList(item.getAtcCode(), true);
				if (atcList != null && atcList.size() > 0) {
					atc = atcList.stream().max(Comparator.comparing(TextAndCodeVO::getValidFrom)).orElse(null);
					atc.setSource(new PriceListSourceVO(atc.getValidFrom(), medicinePrices));
					drug.setAtc(atc);
				} else {
					logger.warn("Unable to fix ATC for DrugPID: " + item.getDrugPID() + " couldn't find ATC: " + item.getAtcCode());
				}
			}
		}
		//Fix FormCode if needed
		if (item.getFormCode() != null) {
			TextAndCodeVO form = stamdataFacade.getFormbetegnelse(timeService.currentLocalDateTime(), item.getFormCode());
			if (form != null) {
				form.setSource(new PriceListSourceVO(form.getValidFrom(), medicinePrices));
				drug.setForm(form);
			} else {
				List<TextAndCodeVO> formList = stamDataDAO.getFormbetegnelseList(item.getFormCode(), true);
				if (formList != null && formList.size() > 0) {
					form = formList.stream().max(Comparator.comparing(TextAndCodeVO::getValidFrom)).orElse(null);
					form.setSource(new PriceListSourceVO(form.getValidFrom(), medicinePrices));
					drug.setForm(form);
				} else {
					logger.warn("Unable to fix FormCode for DrugPID: " + item.getDrugPID() + " couldn't find FormCode: " + item.getFormCode());
				}
			}
		}
		drugMedicationOverview.setDrug(drug);
	}

	private void sendAuditlogForPatients(List<PersonIdentifierVO> personsUpdated) {
		RequestContext.setWhitelistingVO(new WhitelistingVO(
				new CallingSystemIdentificationVO(),
				new CallingOrganisationIdentificationVO(
						"Sundhedsdatastyrelsen",
						"Fejlrettelse, Sundhedsdatastyrelsen",
						new OrgUsingId(Constants.ADM_IDENTIFIER, OrgUsingId.OrgUsingIdNameFormatType.CVRNUMBER)),
				"System"
		));
		RequestContext.get().setUserName("System Bruger");
		RequestContext.get().setOrganisationName("Fejlrettelse, Sundhedsdatastyrelsen");
		RequestContext.get().setCvr(Constants.ADM_IDENTIFIER);
		RequestContext.get().setYderNummer(null);
		RequestContext.setRequestPurpose(new RequestPurposeVO("Rettelse af fejl data"));
		RequestContext.setValidatedRole(new ValidatedRole(Role.System, new ArrayList<>()));
		RequestContext.get().setValidatedRole(Role.System);
		RequestContext.get().setAccessType(SecurityCredentials.ACCESS_TYPE_CONSOLE);
		RequestContext.get().setSystem("FMK Data-repair: SL");
		RequestContext.get().setSystemVersion("1");
		RequestContext.get().setRequestedRole("System");
		RequestContext.get().setLevel(0);
		RequestContext.get().setRemoteAddress("LocalSystem");
		RequestContext.get().setServiceVersion(FMKVersion.fmkV144.getVersion());
		RequestContext.get().setMinlogCriticality(null);

		for (PersonIdentifierVO entry : personsUpdated) {

			logger.info("Sending auditlog to: " + entry + " for repair");
			RequestContext.get().setPersonCPR(entry);
			RequestContext.get().setSubjectCpr(PersonIdentifierVO.fromCPR("0000000000")); //TODO: REPLACE ME?!?
			RequestContext.get().setPersonIdentifierFromRequest(entry);
			RequestContext.get().setRequestSpecificParameters("FMK-SourceLocalRepair-" + timeService.currentTimeMillis());
			RequestContext.get().setAdditionalUserInfo("FMK-SourceLocalRepair-" + timeService.currentTimeMillis());
			RequestContext.get().setLocalDateTime(LocalDateTime.now());
			long timestamp = LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli();
			RequestContext.get().setMessageId("SOURCE-LOCAL-REPAIR-" + timestamp);
			RequestContext.get().setMinLogId("SOURCE-LOCAL-REPAIR-" + timestamp);
			try {
				auditLogger.log("ws.updateMedicineCard", 1L);
				logger.info("Sent auditlog to: " + entry + " for source local repair");
			} catch (Exception e) {
				logger.error("Failed to send auditlog for nonClinical repair. Person:" + entry);
			}
		}
	}

	public static ModificatorVO makeDataUpdaterModificator(LocalDateTime date) {
		return ModificatorVO.makeOther(
				new ModificatorOtherPersonVO("System bruger", "" , "Fejlrettelse, Sundhedsdatastyrelsen"),
				Role.System,
				OrganisationVO.makeAdministratorOrganisation(),
				date);
	}

	private enum Workingtable {
		DrugMedication,
		Effectuation,
		Package,
		PlannedDispensing,
		PackagedDrug
	}

	private static class LocalUpdate {
		//Shared
		private PersonIdentifierVO person;
		private Long drugPID;
		private Long workingPID;
		private String drugLinkedToTable;

		//Things to track for DrugMedication
		private Long dmPID;
		private Long dmIdentifier;
		private Long dmVersion;

		//Things to update (possibly)
		private Long drugId;
		private String atcCode;
		private String formCode;
		private Integer indicationCode;

		//Updating latest version?
		private LocalDateTime dmValidTo;

		public void setPerson(PersonIdentifierVO person) {
			this.person = person;
		}

		public void setDrugPID(Long drugPID) {
			this.drugPID = drugPID;
		}

		public Long getWorkingPID() {
			return workingPID;
		}

		public void setWorkingPID(Long workingPID) {
			this.workingPID = workingPID;
		}

		public String getDrugLinkedToTable() {
			return drugLinkedToTable;
		}

		public void setDrugLinkedToTable(String drugLinkedToTable) {
			this.drugLinkedToTable = drugLinkedToTable;
		}

		public void setDmPID(Long dmPID) {
			this.dmPID = dmPID;
		}

		public void setDmIdentifier(Long dmIdentifier) {
			this.dmIdentifier = dmIdentifier;
		}

		public void setDmVersion(Long dmVersion) {
			this.dmVersion = dmVersion;
		}

		public void setDrugId(Long drugId) {
			this.drugId = drugId;
		}

		public void setIndicationCode(Integer indicationCode) {
			this.indicationCode = indicationCode;
		}

		public void setDmValidTo(LocalDateTime dmValidTo) {
			this.dmValidTo = dmValidTo;
		}

		public void setAtcCode(String atcCode) {
			this.atcCode = atcCode;
		}

		public void setFormCode(String formCode) {
			this.formCode = formCode;
		}

		public PersonIdentifierVO getPerson() {
			return person;
		}

		public Long getDmPID() {
			return dmPID;
		}

		public Long getDmIdentifier() {
			return dmIdentifier;
		}

		public Long getDrugPID() {
			return drugPID;
		}

		public Long getDmVersion() {
			return dmVersion;
		}

		public Long getDrugId() {
			return drugId;
		}

		public String getAtcCode() {
			return atcCode;
		}

		public String getFormCode() {
			return formCode;
		}

		public Integer getIndicationCode() {
			return indicationCode;
		}

		public LocalDateTime getDmValidTo() {
			return dmValidTo;
		}

		public String getFixingString() {
			String fixing = "Fixing: ";
			if (drugId != null) {
				fixing += "DrugId: " + drugId + " : ";
			}
			if (atcCode != null) {
				fixing += "ATCCode: " + atcCode + " : ";
			}
			if (formCode != null) {
				fixing += "FormCode: " + formCode + " : ";
			}
			if (indicationCode != null) {
				fixing += "IndicationCode: " + indicationCode;
			}

			return fixing;
		}
	}
}
