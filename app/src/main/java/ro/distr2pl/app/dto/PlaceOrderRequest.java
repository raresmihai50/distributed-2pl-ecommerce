package ro.distr2pl.app.dto;

import java.util.ArrayList;
import java.util.List;

public class PlaceOrderRequest {
    private Integer userId;
    private String paymentMethod = "CARD";
    private List<OrderItemDto> items = new ArrayList<>();
    private boolean simulateFailureAfterWrites = false;
    private boolean simulateMysqlPrepareFailure = false;

    public PlaceOrderRequest() {
    }

    public PlaceOrderRequest(Integer userId, String paymentMethod, List<OrderItemDto> items) {
        this.userId = userId;
        this.paymentMethod = paymentMethod;
        this.items = items;
    }

    public static class OrderItemDto {
        private Integer productId;
        private Integer quantity;

        public OrderItemDto() {
        }

        public OrderItemDto(Integer productId, Integer quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        public Integer getProductId() {
            return productId;
        }

        public void setProductId(Integer productId) {
            this.productId = productId;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public List<OrderItemDto> getItems() {
        return items;
    }

    public void setItems(List<OrderItemDto> items) {
        this.items = items;
    }

    public boolean isSimulateFailureAfterWrites() {
        return simulateFailureAfterWrites;
    }

    public void setSimulateFailureAfterWrites(boolean simulateFailureAfterWrites) {
        this.simulateFailureAfterWrites = simulateFailureAfterWrites;
    }

    public boolean isSimulateMysqlPrepareFailure() {
        return simulateMysqlPrepareFailure;
    }

    public void setSimulateMysqlPrepareFailure(boolean simulateMysqlPrepareFailure) {
        this.simulateMysqlPrepareFailure = simulateMysqlPrepareFailure;
    }
}

