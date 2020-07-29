package dk.medicinkortet.dataupdater;

import dk.medicinkortet.authentication.SecurityCredentials;
import dk.medicinkortet.authentication.ValidatedRole;
import dk.medicinkortet.persistence.auditlog.datafacade.AuditLoggerFacade;
import dk.medicinkortet.persistence.mysql_helpers.CustomJdbcTemplate;
import dk.medicinkortet.requestcontext.RequestContext;
import dk.medicinkortet.services.vo.*;
import dk.medicinkortet.utils.DateUtils;
import dk.medicinkortet.utils.Tuple;
import dk.medicinkortet.ws.requestpurpose.RequestPurposeVO;
import dk.medicinkortet.ws.whitelisting.WhitelistingVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.ocsp.Req;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class NonClinicalModificatorRepair {

	private static Logger logger = LogManager.getLogger(NonClinicalModificatorRepair.class);

	@Autowired private CustomJdbcTemplate jdbcTemplate;
	@Qualifier("auditLogger") @Autowired private AuditLoggerFacade auditLogger;

	private int countTotal = 0;
	private int countUpdated = 0;
	private int countUpdatedFailed = 0;
	private int countExceptions = 0;

	public void update(boolean testMode) {

		String sql = "SELECT DISTINCT(mc.MedicineCardPID), ipid.PersonIdentifier, ipid.PersonIdentifierSource FROM MedicineCards mc " +
				"INNER JOIN InternalPersonIds ipid ON mc.InternalPersonId = ipid.InternalPersonId " +
				"INNER JOIN DrugMedications dm ON mc.MedicineCardPID = dm.MedicineCardPID " +
				"LEFT JOIN " + jdbcTemplate.getSdmDatabase() + ".BarnRelation br ON ipid.PersonIdentifier = br.BarnCPR AND mc.ModifiedBy = br.CPR AND br.ValidFrom <= mc.ModifiedDate AND br.ValidTo >= mc.ModifiedDate " +
				"LEFT JOIN " + jdbcTemplate.getSdmDatabase() + ".Person p ON mc.ModifiedBy = p.CPR AND p.ValidFrom <= mc.ModifiedDate AND p.ValidTo >= mc.ModifiedDate " +
				"LEFT JOIN " + jdbcTemplate.getSdmDatabase() + ".ForaeldreMyndighedRelation fm ON ipid.PersonIdentifier = fm.CPR AND fm.ValidFrom <= mc.ModifiedDate AND fm.ValidTo >= mc.ModifiedDate " +
				"WHERE mc.HashedModifiedNonclinicalByPID IS NULL " +
				"    AND mc.HashedReportedNonclinicalByPID IS NULL " +
				"    AND mc.ValidFrom > '2018-6-18 12:00:00' " +
				"    AND dm.DrugMedicationIdentifier IS NOT NULL " +
				"    AND (dm.HashedModifiedNonclinicalByPID IS NOT NULL OR dm.HashedReportedNonclinicalByPID IS NOT NULL) " +
				"    AND ((br.BarnRelationPID IS NOT NULL " +
				"    AND ((p.Koen IS NOT NULL AND fm.TypeTekst IS NOT NULL) AND ((p.Koen = 'M' AND fm.TypeTekst = 'Far') OR (p.Koen = 'K' AND fm.TypeTekst = 'Mor')))) " +
				"    OR (fm.RelationCpr IS NOT NULL AND fm.RelationCpr = mc.ModifiedBy))";

		List<Tuple<Long, PersonBaseVO.PersonIdentifierVO>> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
					return new Tuple<Long, PersonBaseVO.PersonIdentifierVO>(rs.getLong("mc.MedicineCardPID"),
							new PersonBaseVO.PersonIdentifierVO(rs.getString("ipid.PersonIdentifier"),
									PersonBaseVO.PersonIdentifierVO.Source.fromValue(rs.getString("ipid.PersonIdentifierSource"))));
				}
		);

		List<Long> mcPids = results.stream().map(Tuple::getFirst).distinct().collect(Collectors.toList());
		List<PersonBaseVO.PersonIdentifierVO> cprs = results.stream().map(Tuple::getSecond).distinct().collect(Collectors.toList());

		if (mcPids.isEmpty()) {
			logger.info("No further repair needed");
			return;
		}

		List<Long> repairedSuccessfully = new ArrayList<>();
		for (Long mcPid : mcPids) {
			boolean success = setNonClinicalModificator(mcPid, testMode);
			if (success) {
				repairedSuccessfully.add(mcPid);
			}
		}

		logger.info("Total: " + countTotal);
		logger.info("Updated: " + countUpdated);
		logger.info("Updated attempted but failed: " + countUpdatedFailed);
		logger.info("Exceptions: " + countExceptions);

		if (RequestContext.isSet() && !testMode) {
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
			RequestContext.setRequestPurpose(new RequestPurposeVO("Rettelse af fejl data"));
			RequestContext.setValidatedRole(new ValidatedRole(Role.System, new ArrayList<>()));
			RequestContext.get().setValidatedRole(Role.System);
			RequestContext.get().setAccessType(SecurityCredentials.ACCESS_TYPE_CONSOLE);
			RequestContext.get().setSystem("FMK Data-repair: NC");
			RequestContext.get().setSystemVersion("1");
			RequestContext.get().setRequestedRole("System");
			RequestContext.get().setLevel(0);
			RequestContext.get().setRemoteAddress("LocalSystem");
			RequestContext.get().setServiceVersion("1.0.0");
			RequestContext.get().setMinlogCriticality(null);



			for (Tuple<Long, PersonBaseVO.PersonIdentifierVO> entry : results) {
				if (!repairedSuccessfully.contains(entry.getFirst())) {
					continue; //Don't auditlog those that failed to update
				}
				logger.info("Sending auditlog to: " + entry.getSecond() + " for pid: " + entry.getFirst());
				RequestContext.get().setPersonCPR(entry.getSecond());
				RequestContext.get().setSubjectCpr(PersonBaseVO.PersonIdentifierVO.fromCPR("0000000000")); //TODO: REPLACE ME?!?
				RequestContext.get().setPersonIdentifierFromRequest(entry.getSecond());
				RequestContext.get().setRequestSpecificParameters("NonClinicalRepair-"+entry.getFirst());
				RequestContext.get().setAdditionalUserInfo("FMK-NonClinicalRepair-"+entry.getFirst());
				RequestContext.get().setLocalDateTime(LocalDateTime.now());
				long timestamp = LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli();
				RequestContext.get().setMessageId(entry.getFirst() + "-NC-REPAIR-" + timestamp);
				RequestContext.get().setMinLogId(entry.getFirst() + "-NC-REPAIR-" + timestamp);
				try {
					auditLogger.log("ws.updateMedicineCard", 1L);
				} catch (Exception e) {
					logger.error("Failed to send auditlog for nonClinical repair. PID:" + entry.getFirst() + " Person:" + entry.getSecond());
				}
			}
		}
	}

	public boolean setNonClinicalModificator(Long mcPid, boolean testMode) {

		logger.info("fixing mc with PID: " + mcPid);

		try {
			String sql = "SELECT mcOld.ModifiedBy, mcOld.modifiedDate," +
					" dm.HashedModifiedNonclinicalByPID, dm.ModifiedNonclinicalByDateTime," +
					" dm.HashedReportedNonclinicalByPID, dm.ReportedNonclinicalByDateTime " +
					"FROM MedicineCards mc " +
					"INNER JOIN DrugMedications dm ON mc.MedicineCardPID = dm.MedicineCardPID " +
					"INNER JOIN MedicineCards mcOld ON mc.InternalPersonId = mcOld.InternalPersonId AND mcOld.MedicineCardPID = (" +
					"	SELECT old.MedicineCardPID " +
					"	FROM MedicineCards old " +
					"	WHERE old.VersionID < mc.VersionID " +
					"	AND old.ModifiedBy != mc.ModifiedBy " +
					"   AND old.InternalPersonId = mc.InternalPersonId " +
					"   ORDER BY VersionID DESC " +
					"   LIMIT 1)" +
					"WHERE mc.MedicineCardPID = ?";

			List<mcToRepair> query = jdbcTemplate.query(sql,
					(rs, rowNum) -> {
						String modBy = rs.getString("mcOld.ModifiedBy");
						LocalDateTime modByTime = DateUtils.getLocalDateTimeFromResultSet(rs, "mcOld.modifiedDate");
						Long nonCliMod = rs.getLong("dm.HashedModifiedNonclinicalByPID");
						if (rs.wasNull()) {
							nonCliMod = null;
						}
						Long nonCliRep = rs.getLong("dm.HashedReportedNonclinicalByPID");
						if (rs.wasNull()) {
							nonCliRep = null;
						}
						LocalDateTime nonCliModTime = DateUtils.getLocalDateTimeFromResultSet(rs, "dm.ModifiedNonclinicalByDateTime");
						LocalDateTime nonCliRepTime = DateUtils.getLocalDateTimeFromResultSet(rs, "dm.ReportedNonclinicalByDateTime");


						return new mcToRepair(mcPid, modBy, modByTime, nonCliMod, nonCliModTime, nonCliRep, nonCliRepTime);
					}, mcPid
			);

			if (query.size() > 1) {
				logger.warn("NonClinicalRepair: found two entries for PID? " + mcPid + " aborting");
				countUpdatedFailed++;
				return false;
			}
			if (query.isEmpty()) {
				logger.warn("NonClinicalRepair: didn't find the entry? " + mcPid);
				countUpdatedFailed++;
				return false;
			}

			mcToRepair entry = query.get(0);

			if (entry.oldModifiedBy == null || entry.oldModifiedByDate == null) {
				logger.warn("NonClinicalRepair: didn't find the old correct modifiedBy or Time? " + mcPid + " '" + entry.oldModifiedBy + "' '" + entry.oldModifiedByDate + "'");
				countUpdatedFailed++;
				return false;
			}if (entry.nonCliRepPid == null && entry.nonCliModPid == null) {
				logger.warn("NonClinicalRepair: didn't find the nonClinicalModifiers on DM? " + mcPid);
				countUpdatedFailed++;
				return false;
			} else if (entry.nonCliRepTime == null && entry.nonCliModTime == null) {
				logger.warn("NonClinicalRepair: didn't find the nonClinicalModifiersTime on DM? " + mcPid);
				countUpdatedFailed++;
				return false;
			}

			if (testMode) {
				logger.info("TEST: Repairing mcVersion with PID: '" + entry.mcPID +
						"' Values: ModifiedBy:'" + entry.oldModifiedBy + "' ModifiedDateTime:'" + entry.oldModifiedByDate +
						"' nonClinicalModPid:'" + entry.nonCliModPid + "' nonClinicalModTime:'" + entry.nonCliModTime +
						"' nonClinicalRepPid:'" + entry.nonCliRepPid + "' nonClinicalRepTime:'" + entry.nonCliRepTime + "'");
			} else {
				logger.info("Repairing mcVersion with PID: '" + entry.mcPID +
						"' Values: ModifiedBy:'" + entry.oldModifiedBy + "' ModifiedDateTime:'" + entry.oldModifiedByDate +
						"' nonClinicalModPid:'" + entry.nonCliModPid + "' nonClinicalModTime:'" + entry.nonCliModTime +
						"' nonClinicalRepPid:'" + entry.nonCliRepPid + "' nonClinicalRepTime:'" + entry.nonCliRepTime + "'");

				jdbcTemplate.update("UPDATE MedicineCards SET " +
								"ModifiedBy = ?, " +
								"ModifiedDate = ?, " +
								"HashedModifiedNonclinicalByPID = ?, " +
								"ModifiedNonclinicalByDateTime = ?, " +
								"HashedReportedNonclinicalByPID = ?, " +
								"ReportedNonclinicalByDateTime = ? " +
								"WHERE MedicineCardPID = ?",
						entry.oldModifiedBy, entry.oldModifiedByDate,
						entry.nonCliModPid, entry.nonCliModTime,
						entry.nonCliRepPid, entry.nonCliRepTime,
						entry.mcPID);

				countUpdated++;
			}

		} catch (Exception e) {
			countExceptions++;
			logger.error("Error repairing MC nonClinical pid:" + mcPid, e);
			return false;
		}

		countTotal++;
		return true;
	}

	private class mcToRepair {
		Long mcPID;
		String oldModifiedBy;
		LocalDateTime oldModifiedByDate;
		Long nonCliModPid;
		LocalDateTime nonCliModTime;
		Long nonCliRepPid;
		LocalDateTime nonCliRepTime;

		public mcToRepair(Long mcPID, String oldModifiedBy, LocalDateTime oldModifiedByDate,
						  Long nonCliModPid, LocalDateTime nonCliModTime, Long nonCliRepPid, LocalDateTime nonCliRepTime) {
			this.mcPID = mcPID;
			this.oldModifiedBy = oldModifiedBy;
			this.oldModifiedByDate = oldModifiedByDate;
			this.nonCliModPid = nonCliModPid;
			this.nonCliModTime = nonCliModTime;
			this.nonCliRepPid = nonCliRepPid;
			this.nonCliRepTime = nonCliRepTime;
		}
	}
}
