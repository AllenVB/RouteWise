package com.routewise.recipient;

import java.util.List;

import com.routewise.address.Address;
import com.routewise.address.AddressRepository;
import com.routewise.common.error.ApiException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipientService {

    private final RecipientRepository recipients;
    private final AddressRepository addresses;

    public RecipientService(RecipientRepository recipients, AddressRepository addresses) {
        this.recipients = recipients;
        this.addresses = addresses;
    }

    /** The query fixes its own order, so only page and size are taken from the caller. */
    @Transactional(readOnly = true)
    public Page<RecipientSummary> search(String q, int page, int size) {
        return recipients.search(normalize(q), PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Recipient get(Long id) {
        return recipients.findById(id).orElseThrow(() -> ApiException.notFound("Alıcı"));
    }

    @Transactional(readOnly = true)
    public List<Address> addressesOf(Long recipientId) {
        get(recipientId);   // bilinmeyen alıcı boş liste değil, 404'tür
        return addresses.findByRecipientIdOrderByIdAsc(recipientId);
    }

    /**
     * Blank means "no filter"; LIKE wildcards typed by the user are taken literally.
     */
    static String normalize(String q) {
        if (q == null || q.isBlank()) {
            return "";
        }
        return q.trim().replace("%", "").replace("_", "");
    }
}
