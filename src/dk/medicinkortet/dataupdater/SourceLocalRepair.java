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
import dk.medicinkortet.persistence.mk.mysql_impl.dao.DrugMedicationDAO;
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

		//Find Drugs with wrong source local on DrugId
		String sqlDrugId = "select ipid.PersonIdentifier, ipid.PersonIdentifierSource, dm.*, d.*"
				+ " FROM DrugMedications dm "
				+ " INNER JOIN MedicineCards mc on dm.MedicineCardPID=mc.MedicineCardPID "
				+ " INNER JOIN InternalPersonIds ipid on mc.InternalPersonId = ipid.InternalPersonId "
				+ " INNER JOIN Drugs d on dm.DrugPID = d.DrugPID "
				+ " WHERE d.DrugId IS NOT NULL AND d.DrugIdSource = 'Local' AND d.DrugId IN (SELECT DISTINCT(DrugID) FROM " + jdbcTemplate.getSdmDatabase() + ".Laegemiddel)";

		List<LocalUpdate> itemsToFix = jdbcTemplate.query(sqlDrugId,
				(rs, rowNum) -> {
					PersonIdentifierVO person = new PersonIdentifierVO(rs.getString("PersonIdentifier"), PersonIdentifierVO.Source.fromValue(rs.getString("PersonIdentifierSource")));
					long dmPID = rs.getLong("dm.DrugMedicationPID");
					long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
					long dmVersion = rs.getLong("dm.Version");
					LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
					long drugPID = rs.getLong("d.DrugPID");
					long drugId = rs.getLong("d.DrugId");
					return new LocalUpdate(person, dmPID, dmIdentifier, drugPID, dmVersion, drugId, dmValidTo);
				});

		//Find Drugs with wrong source local on ATC
		String sqlATC = "select ipid.PersonIdentifier, ipid.PersonIdentifierSource, dm.*, d.*"
				+ " FROM DrugMedications dm "
				+ " INNER JOIN MedicineCards mc on dm.MedicineCardPID=mc.MedicineCardPID "
				+ " INNER JOIN InternalPersonIds ipid on mc.InternalPersonId = ipid.InternalPersonId "
				+ " INNER JOIN Drugs d on dm.DrugPID = d.DrugPID "
				+ " WHERE d.ATCCode IS NOT NULL AND d.ATCCodeSource = 'Local' AND d.ATCCode IN (SELECT DISTINCT(ATC) FROM " + jdbcTemplate.getSdmDatabase() + ".ATC)";

		jdbcTemplate.query(sqlATC,
				rs -> {
					PersonIdentifierVO person = new PersonIdentifierVO(rs.getString("PersonIdentifier"), PersonIdentifierVO.Source.fromValue(rs.getString("PersonIdentifierSource")));
					long dmPID = rs.getLong("dm.DrugMedicationPID");
					long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
					long dmVersion = rs.getLong("dm.Version");
					LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
					long drugPID = rs.getLong("d.DrugPID");
					String atcCode = rs.getString("d.ATCCode");
					LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDmPID() == dmPID && e.getDmDrugPID() == drugPID).findFirst().orElse(null);
					if (currentUpdate != null) {
						currentUpdate.setAtcCode(atcCode);
					} else {
						itemsToFix.add(new LocalUpdate(person, dmPID, dmIdentifier, drugPID, dmVersion, atcCode, dmValidTo));
					}
				});

		//Find Drugs with wrong source local on FormCode
		String sqlFormCode = "select ipid.PersonIdentifier, ipid.PersonIdentifierSource, dm.*, d.*"
				+ " FROM DrugMedications dm "
				+ " INNER JOIN MedicineCards mc on dm.MedicineCardPID=mc.MedicineCardPID "
				+ " INNER JOIN InternalPersonIds ipid on mc.InternalPersonId = ipid.InternalPersonId "
				+ " INNER JOIN Drugs d on dm.DrugPID = d.DrugPID "
				+ " WHERE d.DrugFormCode IS NOT NULL AND d.DrugFormCodeSource = 'Local' AND d.DrugId IN (SELECT DISTINCT(Kode) FROM " + jdbcTemplate.getSdmDatabase() + ".Formbetegnelse)";

		jdbcTemplate.query(sqlFormCode,
				rs -> {
					PersonIdentifierVO person = new PersonIdentifierVO(rs.getString("PersonIdentifier"), PersonIdentifierVO.Source.fromValue(rs.getString("PersonIdentifierSource")));
					long dmPID = rs.getLong("dm.DrugMedicationPID");
					long dmIdentifier = rs.getLong("dm.DrugMedicationIdentifier");
					long dmVersion = rs.getLong("dm.Version");
					LocalDateTime dmValidTo = rs.getTimestamp("dm.ValidTo").toLocalDateTime();
					long drugPID = rs.getLong("d.DrugPID");
					String formCode = rs.getString("d.DrugFormCode");
					LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDmPID() == dmPID && e.getDmDrugPID() == drugPID).findFirst().orElse(null);
					if (currentUpdate != null) {
						currentUpdate.setFormCode(formCode);
					} else {
						itemsToFix.add(new LocalUpdate(person, dmPID, dmIdentifier, drugPID, dmVersion, dmValidTo, formCode));
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
					LocalUpdate currentUpdate = itemsToFix.stream().filter(e -> e.getDmPID() == dmPID && e.getDmDrugPID() == drugPID).findFirst().orElse(null);
					if (currentUpdate != null) {
						currentUpdate.setIndicationCode(indication);
					} else {
						itemsToFix.add(new LocalUpdate(person, dmPID, dmIdentifier, drugPID, dmVersion, indication, dmValidTo));
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

			logger.info("Work item: " + item.getPerson().toString() +
					" DM: " + item.getDmIdentifier() + ":" + item.getDmVersion() + " " +
					item.getFixingString());

			if (!testMode) {
				try {
					if (item.getDmValidTo().isAfter(timeService.currentLocalDateTime())) {
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
							item.getPerson().toString() + " : " + item.getDmDrugPID() +
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
							item.getPerson().toString() + " : " + item.getDmDrugPID() +
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

		jdbcTemplate.update(updateSql, item.getDmDrugPID());
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
				logger.warn("Unable to fix Drug for DrugPID: " + item.getDmDrugPID() + " couldn't find stamDrug for drugId: " + item.getDrugId());
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
					logger.warn("Unable to fix ATC for DrugPID: " + item.getDmDrugPID() + " couldn't find ATC: " + item.getAtcCode());
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
					logger.warn("Unable to fix FormCode for DrugPID: " + item.getDmDrugPID() + " couldn't find FormCode: " + item.getAtcCode());
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
		RequestContext.get().setSystem("FMK Data-repair");
		RequestContext.get().setSystemVersion("1");
		RequestContext.get().setRequestedRole("System");
		RequestContext.get().setLevel(0);
		RequestContext.get().setRemoteAddress("LocalSystem");
		RequestContext.get().setServiceVersion("1.0.0");
		RequestContext.get().setMinlogCriticality(null);

		for (PersonIdentifierVO entry : personsUpdated) {

			logger.info("Sending auditlog to: " + entry + " for pid: " + entry);
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
		//Things to track
		private final PersonIdentifierVO person;
		private final long dmPID;
		private final long dmIdentifier;
		private final long dmDrugPID;
		private final long dmVersion;

		//Things to update (possibly)
		private Long drugId;
		private String atcCode;
		private String formCode;
		private Integer indicationCode;

		//Updating latest version?
		private final LocalDateTime dmValidTo;

		//Fix DrugId
		public LocalUpdate(PersonIdentifierVO person, long dmPID, long dmIdentifier, long dmDrugPID, long dmVersion, long dmDrugId, LocalDateTime dmValidTo) {
			this.person = person;
			this.dmPID = dmPID;
			this.dmIdentifier = dmIdentifier;
			this.dmDrugPID = dmDrugPID;
			this.dmVersion = dmVersion;
			this.drugId = dmDrugId;
			this.dmValidTo = dmValidTo;
		}

		//Fix ATC Code
		public LocalUpdate(PersonIdentifierVO person, long dmPID, long dmIdentifier, long dmDrugPID, long dmVersion, String atcCode, LocalDateTime dmValidTo) {
			this.person = person;
			this.dmPID = dmPID;
			this.dmIdentifier = dmIdentifier;
			this.dmDrugPID = dmDrugPID;
			this.dmVersion = dmVersion;
			this.atcCode = atcCode;
			this.dmValidTo = dmValidTo;
		}

		//Fix FormCode
		public LocalUpdate(PersonIdentifierVO person, long dmPID, long dmIdentifier, long dmDrugPID, long dmVersion, LocalDateTime dmValidTo, String formCode) {
			this.person = person;
			this.dmPID = dmPID;
			this.dmIdentifier = dmIdentifier;
			this.dmDrugPID = dmDrugPID;
			this.dmVersion = dmVersion;
			this.formCode = formCode;
			this.dmValidTo = dmValidTo;
		}

		//Fix Indication
		public LocalUpdate(PersonIdentifierVO person, long dmPID, long dmIdentifier, long dmDrugPID, long dmVersion, int indicationCode, LocalDateTime dmValidTo) {
			this.person = person;
			this.dmPID = dmPID;
			this.dmIdentifier = dmIdentifier;
			this.dmDrugPID = dmDrugPID;
			this.dmVersion = dmVersion;
			this.indicationCode = indicationCode;
			this.dmValidTo = dmValidTo;
		}

		public void setAtcCode(String atcCode) {
			this.atcCode = atcCode;
		}

		public void setFormCode(String formCode) {
			this.formCode = formCode;
		}

		public void setIndicationCode(int indicationCode) {
			this.indicationCode = indicationCode;
		}

		public PersonIdentifierVO getPerson() {
			return person;
		}

		public long getDmPID() {
			return dmPID;
		}

		public long getDmIdentifier() {
			return dmIdentifier;
		}

		public long getDmDrugPID() {
			return dmDrugPID;
		}

		public long getDmVersion() {
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
