package dk.medicinkortet.dataupdater;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import dk.medicinkortet.dao.vo.EffectuationOrPrescriptionOrderVO;
import dk.medicinkortet.dao.vo.ModificateValue;
import dk.medicinkortet.exceptions.PrescriptionCommunicationException;
import dk.medicinkortet.exceptions.PrescriptionDoesNotExistException;
import dk.medicinkortet.persistence.mk.datafacade.PatientDataFacade;
import dk.medicinkortet.prescriptions.vo.EffectuationsOnPrescriptionMedication;
import dk.medicinkortet.prescriptions.vo.PatientWithoutCPR;
import dk.medicinkortet.services.prescription.PrescriptionService;
import dk.medicinkortet.services.req_resp.CancelOrderRequest.Order;
import dk.medicinkortet.services.req_resp.CreateAndEffectuatePrescriptionRequest;
import dk.medicinkortet.services.req_resp.CreateAndEffectuatePrescriptionResponse;
import dk.medicinkortet.services.req_resp.CreateOrderRequest.Prescription;
import dk.medicinkortet.services.req_resp.CreatePharmacyEffectuationRequest;
import dk.medicinkortet.services.req_resp.StartEffectuationRequest;
import dk.medicinkortet.services.req_resp.UndoEffectuationRequest.UndoEffectuationObject;
import dk.medicinkortet.services.vo.AuthorisationVO;
import dk.medicinkortet.services.vo.AuthorisedHealthcareProfessionalVO;
import dk.medicinkortet.services.vo.ModificatorVO;
import dk.medicinkortet.services.vo.OrganisationVO;
import dk.medicinkortet.services.vo.PersonBaseVO.PersonIdentifierVO;
import dk.medicinkortet.services.vo.PricelistVersionVO;
import dk.medicinkortet.services.vo.prescriptions.vo.CreatedPharmacyEffectuationVO;
import dk.medicinkortet.services.vo.prescriptions.vo.InvalidatePrescriptionVO;
import dk.medicinkortet.services.vo.prescriptions.vo.PrescriptionMedication;
import dk.medicinkortet.services.vo.prescriptions.vo.PrescriptionsWithOwnerVO;
import dk.medicinkortet.services.vo.prescriptions.vo.StartEffectuationResult;
import dk.nsi.fmk.recepter.common.proto.services.Services.ReplacePrescriptionRequest;
import dk.nsi.fmk.recepter.common.proto.services.Services.ReplacePrescriptionResponse;

@Component
public class DummyPrescriptionService implements PrescriptionService {

	@Override
	public PrescriptionsWithOwnerVO getPrescriptions(PatientDataFacade pdf) throws PrescriptionCommunicationException {
		return new PrescriptionsWithOwnerVO(null, new ArrayList<>());
	}

	@Override
	public PrescriptionsWithOwnerVO getOpenPrescriptions(PatientDataFacade pdf) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PrescriptionsWithOwnerVO getPrescriptions(PatientDataFacade pdf, Collection<Long> identifiers) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CreatePrescriptionResult createPrescription(PatientDataFacade pdf, PatientWithoutCPR patientWithoutCPR,
			AuthorisationVO createdByAuthorisation, boolean forUseInPractice, boolean forPersonWithoutCpr, LocalDateTime now,
			ModificateValue<PrescriptionMedication> prescription) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<Long> createPrescriptions(PatientDataFacade pdf, PatientWithoutCPR patientWithoutCPR,
			AuthorisationVO createdByAuthorisation, boolean forUseInPractice, boolean forPersonWithoutCpr, LocalDateTime now,
			List<ModificateValue<PrescriptionMedication>> prescriptions) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CancelPrescriptionReturnObject cancelPrescriptionMedication(PatientDataFacade pdf,
			List<CancelPrescription> prescriptions) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PricelistVersionVO mapPricelistVersionIfMissing(PricelistVersionVO pricelistVersion) {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public CreateOrderReturnObject createOrder(PatientDataFacade pdf, ModificatorVO modificator,
			List<Prescription> requests) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CancelOrderReturnObject cancelOrder(PatientDataFacade pdf, ModificatorVO modificator, List<Order> requests) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public GetNewOrdersForPharmacyReturnObject getNewOrdersForPharmacy(String locationNumber,
			Integer maxNumberOfPrescriptionsReturned) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void acknowledgeOrders(long locationNumber, Map<Long, Long> prescriptionOrderMap) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void validatePrescriptions(PatientDataFacade pdf, Collection<Long> ids)
			throws PrescriptionDoesNotExistException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Map<Long, EffectuationsOnPrescriptionMedication> getEffectuationsNotMatching(PatientDataFacade pdf,
			LocalDateTime from, LocalDateTime to, Set<Long> presciptionMedicationIdentifiers, int resultLimit) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Long, EffectuationsOnPrescriptionMedication> getEffectuationsMatching(PatientDataFacade pdf, LocalDateTime from,
			LocalDateTime to, Set<Long> presciptionMedicationIdentifiers, int resultLimit) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Long, EffectuationsOnPrescriptionMedication> getAllEffectuations(PatientDataFacade pdf, LocalDateTime from,
			LocalDateTime to, int resultLimit) {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public Collection<Long> invalidatePrescriptionMedication(PatientDataFacade pdf,
			Collection<InvalidatePrescriptionVO> invalidatePrescriptions, long locationNumber, String userId,
			long versionCheckKey) {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public Collection<CreatedPharmacyEffectuationVO> createPharmacyEffectuation(PatientDataFacade patient,
			CreatePharmacyEffectuationRequest createPharmacyEffectuationRequest) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Long> terminatePrescription(PatientDataFacade patient, ModificatorVO modificator,
			Collection<Long> prescriptionIdsToTerminate) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Long> reopenPrescriptionMedication(PatientDataFacade patient, ModificatorVO modificator,
			Collection<Long> reopenPrescriptions) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AbortEffectuationReturnObject abortEffectuation(PatientDataFacade patient, ModificatorVO modificator,
			List<dk.medicinkortet.services.req_resp.AbortEffectuationRequest.Prescription> prescriptions) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public UndoEffectuationReturnObject undoEffectuation(PatientDataFacade patient, ModificatorVO modificator,
			List<UndoEffectuationObject> undoEffectuationObjects) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SearchMedicationsResult searchMedications(PatientWithoutCPR patient, boolean isPersonWithoutCPR,
			AuthorisedHealthcareProfessionalVO issuer, OrganisationVO issuerOrganisation) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DumpRestorePrepareResult prepareRestorePerson(String dump) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DumpRestorePersonResult restorePerson(DumpRestorePersonRequest req) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PrescriptionsWithOwnerVO getPrescriptions(PatientDataFacade pdf, LocalDateTime timestamp)
			throws PrescriptionCommunicationException {

		return new PrescriptionsWithOwnerVO(null, new ArrayList<>());
	}

	@Override
	public CreateAndEffectuatePrescriptionResponse createAndEffectuatePrescription(PatientDataFacade patient,
			ModificatorVO modificator, CreateAndEffectuatePrescriptionRequest createAndEffectuatePrescriptionRequest) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void administrativeDeletePrescription(PersonIdentifierVO personCpr, long prescriptionMedicationId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void administrativeDeleteEffectuation(PersonIdentifierVO cpr, long prescriptionIdentifier,
			long effectuationIdentifier) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void administrativeDeleteOrder(PersonIdentifierVO cpr, long prescriptionIdentifier, long orderIdentifier) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public CreateOrderReturnObject orderEffectuation(PrescriptionMedication prescriptionMedication,
			EffectuationOrPrescriptionOrderVO effectuationOrder, PatientDataFacade pdf) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public LatestPrescriptionChanges getLatestStatusChange(PatientDataFacade pdf) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void resetPerson(PersonIdentifierVO cpr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public StartEffectuationResult startEffectuation(PatientDataFacade pdf,
			StartEffectuationRequest startEffectuationRequest, boolean isDDPeriod, boolean isPreflight) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DumpRestorePersonResult dumpPerson(PersonIdentifierVO patientCpr) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ReplacePrescriptionResponse replacePrescription(ReplacePrescriptionRequest req) {
		// TODO Auto-generated method stub
		return null;
	}
}
