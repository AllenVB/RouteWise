package com.routewise.address;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByRecipientIdOrderByIdAsc(Long recipientId);

    List<Address> findByVerificationStatusOrderByCreatedAtAsc(VerificationStatus status);
}
