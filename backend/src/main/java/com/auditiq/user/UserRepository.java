package com.auditiq.user;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    @Query("""
            select u from User u
            where (:search is null
                   or lower(u.username) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.fullName) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.email) like lower(concat('%', cast(:search as string), '%')))
            """)
    Page<User> search(@Param("search") String search, Pageable pageable);
}
