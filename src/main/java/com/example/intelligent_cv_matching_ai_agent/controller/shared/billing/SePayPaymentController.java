package com.example.intelligent_cv_matching_ai_agent.controller.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.billing.PaymentOrderRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.shared.billing.SePayCheckoutResponse;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.SePayPaymentService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class SePayPaymentController {

    private final SePayPaymentService service;

    public SePayPaymentController(
        SePayPaymentService service) {
        this.service = service;
    }

    @PostMapping("/sepay/checkout")
    public SePayCheckoutResponse create(
        @Valid
        @RequestBody
        PaymentOrderRequest request) {
        return service.createCheckout(request);
    }

    @PostMapping("/orders/{id}/sepay-checkout")
    public SePayCheckoutResponse checkout(
        @PathVariable
        UUID id) {
        return service.checkout(id);
    }

    @PostMapping("/sepay/ipn")
    public Map<String, Object> ipn(
        @RequestHeader(name = "X-Secret-Key", required = false)
        String secret,
        @RequestBody
        String body) {
        return service.ipn(secret, body);
    }
}
