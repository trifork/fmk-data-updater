package dk.medicinkortet.dataupdater;

import dk.medicinkortet.advis.AsyncAdvisFacade;
import dk.medicinkortet.authentication.SecurityCredentials;
import dk.medicinkortet.authentication.ValidatedRole;
import dk.medicinkortet.dao.vo.ModificateValue;
import dk.medicinkortet.dao.vo.UpdateDrugmedicationVO;
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

	public void runRepair(boolean testMode) {

		countTotal = 0;
		countUpdated = 0;
		countFixed = 0;
		countExceptions = 0;

		//Find Drugs with wrong source local on some element, and find the parent it's linked to.
		String sqlBase = "SELECT "
				+ " d.*,"
				//Select Person
				+ " coalesce(ipidDm.PersonIdentifier, ipidEff.PersonIdentifier, "
				+ " ipidEffPack.PersonIdentifier, ipidDD.PersonIdentifier, ipidDD2.PersonIdentifier) as personIdentifier,"
				+ " coalesce(ipidDm.PersonIdentifierSource, ipidEff.PersonIdentifierSource,"
				+ " ipidEffPack.PersonIdentifierSource, ipidDD.PersonIdentifierSource, ipidDD2.PersonIdentifierSource) as personIdentifierSource,"
				//Select PID working on
				+ " coalesce(dm.DrugMedicationPID, eff.EffectuationPID, "
				+ " pack.PackagePID, packDrug.PackagedDrugPID, plannedDrug.PlannedDispensingPID) as workingPID,"
				//Boolean working table for logger
				+ " dm.DrugMedicationPID IS NOT NULL AS isDm,"
				+ " eff.EffectuationPID IS NOT NULL AS isEff,"
				+ " pack.DrugPID IS NOT NULL AS isPack,"
				+ " packDrug.Drug IS NOT NULL AS isPackedDrug,"
				+ " plannedDrug.SubstitutedDrugPID IS NOT NULL AS isPlanned,"
				//Get DM in case we need to update+advis
				+ " dm.* "
				//Start with problem table
				+ " FROM Drugs d "
				//Is it a DrugMedication?
				+ " LEFT JOIN DrugMedications dm on d.DrugPID = dm.DrugPID "
				+ " LEFT JOIN MedicineCards mcdm on dm.MedicineCardPID = mcdm.MedicineCardPID "
				+ " LEFT JOIN InternalPersonIds ipidDm on mcdm.InternalPersonId = ipidDm.InternalPersonId "
				//Is is an Effectuation?
				+ " LEFT JOIN Effectuations eff on d.DrugPID = eff.DrugPID "
				+ " LEFT JOIN DrugMedications effDm on eff.DrugMedicationPID = effDm.DrugMedicationPID "
				+ " LEFT JOIN MedicineCards mcEff on effDm.MedicineCardPID = mcEff.MedicineCardPID "
				+ " LEFT JOIN InternalPersonIds ipidEff on mcEff.InternalPersonId = ipidEff.InternalPersonId "
				//Is it a Package?
				+ " LEFT JOIN Packages pack on d.DrugPID = pack.DrugPID "
				+ " LEFT JOIN Effectuations effPack on pack.PackagePID = effPack.PackagePID "
				+ " LEFT JOIN DrugMedications effPackDm on effPack.DrugMedicationPID = effPackDm.DrugMedicationPID "
				+ " LEFT JOIN MedicineCards mcEffPack on effPackDm.MedicineCardPID = mcEffPack.MedicineCardPID "
				+ " LEFT JOIN InternalPersonIds ipidEffPack on mcEffPack.InternalPersonId = ipidEffPack.InternalPersonId "
				//Is is a PackagedDrug? (DD)
				+ " LEFT JOIN PackagedDrug packDrug on d.DrugPID = packDrug.Drug "
				+ " LEFT JOIN DoseDispensingPeriods ddPeriod on packDrug.DoseDispensingPeriodIdentifier = ddPeriod.DoseDispensingPeriodIdentifier AND ddPeriod.ValidTo > now()"
				+ " LEFT JOIN DoseDispensingCards ddCard on ddPeriod.DoseDispensingCardIdentifier = ddCard.DoseDispensingCardIdentifier"
				+" AND ddCard.ValidFrom <= ddPeriod.ValidFrom AND ddCard.ValidTo > ddPeriod.ValidFrom"
				+ " LEFT JOIN InternalPersonIds ipidDD on ddCard.InternalPersonId = ipidDD.InternalPersonId "
				//Is it a PlannedDispensing (substituded drug)?
				+ " LEFT JOIN PlannedDispensings plannedDrug on d.DrugPID = plannedDrug.SubstitutedDrugPID "
				+ " LEFT JOIN DoseDispensingCards ddCardPlanned on ddPeriod.DoseDispensingCardIdentifier = ddCardPlanned.DoseDispensingCardIdentifier"
				+ " AND ddCardPlanned.ValidFrom <= plannedDrug.ValidFrom AND ddCardPlanned.ValidTo > plannedDrug.ValidFrom"
				+ " LEFT JOIN InternalPersonIds ipidDD2 on ddCardPlanned.InternalPersonId = ipidDD2.InternalPersonId";

		String sqlDrugId = sqlBase
				+ " WHERE d.DrugId IS NOT NULL AND d.DrugIdSource = 'Local' AND d.DrugId IN (SELECT DISTINCT(DrugID) FROM " +
				jdbcTemplate.getSdmDatabase() + ".Laegemiddel)";

		List<LocalUpdate> itemsToFix = new ArrayList<>();

		jdbcTemplate.query(sqlDrugId,
				rs -> {
					long drugPID = rs.getLong("d.DrugPID");

					LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDrugPID() == drugPID).findFirst().orElse(null);
					if (currentUpdate != null) {
						//Ignore, this is because of duplicated.
					} else {
						LocalUpdate update = new LocalUpdate();

						boolean isDm = rs.getBoolean("isDm");
						boolean isEff = rs.getBoolean("isEff");
						boolean isPack = rs.getBoolean("isPack");
						boolean isPackagedDrug = rs.getBoolean("isPackedDrug");
						boolean isPlanned = rs.getBoolean("isPlanned");


						if (isDm) {
							long dmPID = rs.getLong("dm.DrugMedicationPID");
							long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
							long dmVersion = rs.getLong("dm.Version");
							LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
							update.setDmPID(dmPID);
							update.setDmIdentifier(dmIdentifier);
							update.setDmVersion(dmVersion);
							update.setDmValidTo(dmValidTo);
							update.setDrugLinkedToTable("DrugMedications");
						} else if (isEff) {
							update.setDrugLinkedToTable("Effectuations");
						} else if (isPack) {
							update.setDrugLinkedToTable("Packages");
						} else if (isPackagedDrug) {
							update.setDrugLinkedToTable("PackagedDrug");
						} else if (isPlanned) {
							update.setDrugLinkedToTable("PlannedDispensings");
						} else {
							logger.warn("NOT fixing DrugId DrugPID: " + drugPID + " couldn't determine table its linked to");
							return;
						}

						String personIdentifier = rs.getString("personIdentifier");
						String source = rs.getString("personIdentifierSource");
						logger.info("Found work for person: " + personIdentifier + ":" + source);

						if (personIdentifier == null || source == null) {
							logger.info("Person not found!, PID: " + drugPID + " DM,Eff,Pack,Packaged,Planned?: " + isDm + " " + isEff + " " + isPack + " " + isPackagedDrug + " " + isPlanned);
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

		//Find Drugs with wrong source local on ATC
		String sqlATC = sqlBase
				+ " WHERE d.ATCCode IS NOT NULL AND d.ATCCodeSource = 'Local' AND d.ATCCode IN (SELECT DISTINCT(ATC) FROM " +
				jdbcTemplate.getSdmDatabase() + ".ATC)";

		jdbcTemplate.query(sqlATC,
				rs -> {
					long drugPID = rs.getLong("d.DrugPID");
					String atc = rs.getString("d.ATCCode");

					LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDrugPID() == drugPID).findFirst().orElse(null);
					if (currentUpdate != null) {
						currentUpdate.setAtcCode(atc);
					} else {
						LocalUpdate update = new LocalUpdate();

						boolean isDm = rs.getBoolean("isDm");
						boolean isEff = rs.getBoolean("isEff");
						boolean isPack = rs.getBoolean("isPack");
						boolean isPackagedDrug = rs.getBoolean("isPackedDrug");
						boolean isPlanned = rs.getBoolean("isPlanned");

						if (isDm) {
							long dmPID = rs.getLong("dm.DrugMedicationPID");
							long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
							long dmVersion = rs.getLong("dm.Version");
							LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
							update.setDmPID(dmPID);
							update.setDmIdentifier(dmIdentifier);
							update.setDmVersion(dmVersion);
							update.setDmValidTo(dmValidTo);
							update.setDrugLinkedToTable("DrugMedications");
						} else if (isEff) {
							update.setDrugLinkedToTable("Effectuations");
						} else if (isPack) {
							update.setDrugLinkedToTable("Packages");
						} else if (isPackagedDrug) {
							update.setDrugLinkedToTable("PackagedDrug");
						} else if (isPlanned) {
							update.setDrugLinkedToTable("PlannedDispensings");
						} else {
							logger.warn("NOT fixing ATC for DrugPID: " + drugPID + " couldn't determine table its linked to");
							return;
						}

						String personIdentifier = rs.getString("personIdentifier");
						String source = rs.getString("personIdentifierSource");
						logger.info("Found work for person: " + personIdentifier + ":" + source);

						if (personIdentifier == null || source == null) {
							logger.info("Person not found!, PID: " + drugPID + " DM,Eff,Pack,Packaged,Planned?: " + isDm + " " + isEff + " " + isPack + " " + isPackagedDrug + " " + isPlanned);
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

		//Find Drugs with wrong source local on FormCode
		String sqlFormCode = sqlBase
				+ " WHERE d.DrugFormCode IS NOT NULL AND d.DrugFormCodeSource = 'Local' AND d.DrugId IN (SELECT DISTINCT(Kode) FROM " +
				jdbcTemplate.getSdmDatabase() + ".Formbetegnelse)";

		jdbcTemplate.query(sqlFormCode,
				rs -> {
					long drugPID = rs.getLong("d.DrugPID");
					String formCode = rs.getString("d.DrugFormCode");

					LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDrugPID() == drugPID).findFirst().orElse(null);
					if (currentUpdate != null) {
						currentUpdate.setFormCode(formCode);
					} else {
						LocalUpdate update = new LocalUpdate();

						boolean isDm = rs.getBoolean("isDm");
						boolean isEff = rs.getBoolean("isEff");
						boolean isPack = rs.getBoolean("isPack");
						boolean isPackagedDrug = rs.getBoolean("isPackedDrug");
						boolean isPlanned = rs.getBoolean("isPlanned");

						if (isDm) {
							long dmPID = rs.getLong("dm.DrugMedicationPID");
							long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
							long dmVersion = rs.getLong("dm.Version");
							LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
							update.setDmPID(dmPID);
							update.setDmIdentifier(dmIdentifier);
							update.setDmVersion(dmVersion);
							update.setDmValidTo(dmValidTo);
							update.setDrugLinkedToTable("DrugMedications");
						} else if (isEff) {
							update.setDrugLinkedToTable("Effectuations");
						} else if (isPack) {
							update.setDrugLinkedToTable("Packages");
						} else if (isPackagedDrug) {
							update.setDrugLinkedToTable("PackagedDrug");
						} else if (isPlanned) {
							update.setDrugLinkedToTable("PlannedDispensings");
						} else {
							logger.warn("NOT fixing FormCode for DrugPID: " + drugPID + " couldn't determine table its linked to");
							return;
						}

						String personIdentifier = rs.getString("personIdentifier");
						String source = rs.getString("personIdentifierSource");

						if (personIdentifier == null || source == null) {
							logger.info("Person not found!, PID: " + drugPID + " DM,Eff,Pack,Packaged,Planned?: " + isDm + " " + isEff + " " + isPack + " " + isPackagedDrug + " " + isPlanned);
							return;
						}

						PersonIdentifierVO person = new PersonIdentifierVO(personIdentifier,
								PersonIdentifierVO.Source.fromValue(source));

						long workingPID = rs.getLong("workingPID");
						update.setPerson(person);
						update.setDrugPID(drugPID);
						update.setAtcCode(formCode);
						update.setWorkingPID(workingPID);

						itemsToFix.add(update);
					}
				});

		//Find DrugMedications with wrong source local on Indication
		String sqlIndication = "select ipid.PersonIdentifier, ipid.PersonIdentifierSource, dm.*, d.*"
				+ " FROM DrugMedications dm "
				+ " INNER JOIN MedicineCards mc on dm.MedicineCardPID=mc.MedicineCardPID "
				+ " INNER JOIN InternalPersonIds ipid on mc.InternalPersonId = ipid.InternalPersonId "
				+ " INNER JOIN Drugs d on dm.DrugPID = d.DrugPID "
				+ " WHERE dm.IndicationCode IS NOT NULL AND dm.IndicationCodeSource = 'Local' AND dm.IndicationCode IN (SELECT DISTINCT(IndikationKode) FROM " + jdbcTemplate.getSdmDatabase() + ".Indikation)";

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

		logger.info("SourceLocalRepair, found " + itemsToFix.size() + " items to repair.");

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

	}

	public void updateSourceLocal(List<LocalUpdate> itemsToFix, List<PersonIdentifierVO> personsUpdated, boolean testMode) {

		for (LocalUpdate item : itemsToFix) {

			logger.info("Person: " +item.getPerson().toString() +
					" Work item: "  +
					" DrugPID: " + item.getDrugPID() + " " +
					" LinkedTo: " + item.getDrugLinkedToTable() + " with PID: " + item.getWorkingPID() + " " +
					item.getFixingString());

			if (!testMode) {
				try {
					if (item.getDmVersion() != null &&
							item.getDmValidTo() != null &&
							item.getDmIdentifier() != null &&
							item.getDmValidTo().isAfter(timeService.currentLocalDateTime())) {
						logger.info("Fixing latest version of DM: " + item.getDmIdentifier() + ":" + item.getDmVersion() +
								" creating new version and sending advis.");

						MyPatientDataFacade patientDF = new MyPatientDataFacade(daoHolder, item.getPerson(), stamdataFacade, timeService.currentLocalDateTime());
						ModificatorVO modifiedBy = makeDataUpdaterModificator(LocalDateTime.now());

						//StamdataDrugUpdater will already fix a lot of the errors, but we'll override these just to be sure.
						MedicineCardDataFacade mc = patientDF.getCurrentMedicineCard();

						Collection<DrugMedicationOverview> dms = mc.getDrugMedications();

						DrugMedicationOverview drugMedicationOverview = dms.stream()
								.filter(dm -> dm.getIdentifier().equals(item.getDmIdentifier())).findFirst().get();
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

						ModificateValue<UpdateDrugmedicationVO> modificateUpdateDrugMedication = new ModificateValue<UpdateDrugmedicationVO>(
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
					logger.error("Error creating new DM version! - " +
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
							item.getPerson().toString() + " : " + item.getDrugPID() +
							" : " + item.getDmIdentifier() + " : " + item.getFixingString(), e);
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
			TextAndCodeVO form = stamdataFacade.getFormbetegnelse(timeService.currentLocalDateTime(), item.getAtcCode());
			if (form != null) {
				form.setSource(new PriceListSourceVO(form.getValidFrom(), medicinePrices));
				drug.setForm(form);
			} else {
				List<TextAndCodeVO> formList = stamDataDAO.getFormbetegnelseList(item.getAtcCode(), true);
				if (formList != null && formList.size() > 0) {
					form = formList.stream().max(Comparator.comparing(TextAndCodeVO::getValidFrom)).orElse(null);
					form.setSource(new PriceListSourceVO(form.getValidFrom(), medicinePrices));
					drug.setForm(form);
				} else {
					logger.warn("Unable to fix FormCode for DrugPID: " + item.getDrugPID() + " couldn't find FormCode: " + item.getAtcCode());
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
		RequestContext.get().setServiceVersion("1.0.0");
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
