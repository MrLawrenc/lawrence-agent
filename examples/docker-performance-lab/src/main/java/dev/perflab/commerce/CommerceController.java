package dev.perflab.commerce;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/shop")
public class CommerceController {
    public record CheckoutRequest(String customerId, String sku, int quantity) { }

    private final CommerceService commerce;

    public CommerceController(CommerceService commerce) {
        this.commerce = commerce;
    }

    @GetMapping("/catalog")
    public List<CommerceService.Product> catalog() {
        return commerce.catalog();
    }

    @PostMapping("/orders")
    public ResponseEntity<CommerceService.OrderView> checkout(@RequestBody CheckoutRequest request)
            throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                commerce.checkout(request.customerId(), request.sku(), request.quantity()));
    }

    @GetMapping("/orders/{id}")
    public CommerceService.OrderView order(@PathVariable("id") String id) throws Exception {
        return commerce.findOrder(id);
    }

    @GetMapping("/orders")
    public List<CommerceService.OrderView> recentOrders() {
        return commerce.recentOrders();
    }
}
