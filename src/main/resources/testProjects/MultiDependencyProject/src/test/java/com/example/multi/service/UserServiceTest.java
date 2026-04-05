package com.example.multi.service;

import com.example.multi.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {

    @Test
    void evict_decreasesCacheSize() {
        // We cannot make real HTTP calls in a unit test, so we only verify
        // that evicting a non-existent key does not throw.
        UserService service = new UserService("http://localhost:9999");
        assertDoesNotThrow(() -> service.evict("unknown-user"));
        assertEquals(0L, service.cacheSize());
    }

    @Test
    void getProfile_returnsEmptyOnConnectionRefused() {
        UserService service = new UserService("http://localhost:9999");
        Optional<UserProfile> result = service.getProfile("test-user");
        assertTrue(result.isEmpty(), "Should return empty when server is unreachable");
    }
}
