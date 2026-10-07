package com.parcelpilot.service;

import com.parcelpilot.exception.ApiException;
import com.parcelpilot.model.User;
import com.parcelpilot.repository.OrderRepository;
import com.parcelpilot.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository users;
    private final OrderRepository orders;
    private final AuditService audit;

    public UserService(UserRepository users, OrderRepository orders, AuditService audit) {
        this.users = users;
        this.orders = orders;
        this.audit = audit;
    }

    public User get(String userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again."));
    }

    public User updateSettings(String userId, String theme, Boolean notificationsOn) {
        User user = get(userId);
        if (theme != null) {
            if (!theme.equals("light") && !theme.equals("dark")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Theme must be 'light' or 'dark'.");
            }
            user.getSettings().setTheme(theme);
        }
        if (notificationsOn != null) user.getSettings().setNotificationsOn(notificationsOn);
        return users.save(user);
    }

    /** Deletes the user and every piece of their data (GDPR-style purge). */
    public void deleteAccount(String userId) {
        User user = get(userId);
        orders.deleteAll(orders.findByUserId(userId, org.springframework.data.domain.Sort.unsorted()));
        audit.deleteForUser(userId);
        users.delete(user);
    }
}
