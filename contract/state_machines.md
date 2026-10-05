# V1.2 generated state diagrams

Transition permissions/guards/side effects are authoritative in openapi.yaml x-state-transitions.

## AdjustmentStatus

```mermaid
stateDiagram-v2
    state "REQUESTED" as REQUESTED
    state "APPROVED" as APPROVED
    state "REJECTED" as REJECTED
    state "POSTED" as POSTED
    REQUESTED --> APPROVED
    REQUESTED --> REJECTED
    APPROVED --> POSTED
```

## AdminRequestStatus

```mermaid
stateDiagram-v2
    state "REQUESTED" as REQUESTED
    state "APPROVED" as APPROVED
    state "REJECTED" as REJECTED
    state "DONE" as DONE
```

## BusinessDayRequestStatus

```mermaid
stateDiagram-v2
    state "REQUESTED" as REQUESTED
    state "APPROVED" as APPROVED
    state "REJECTED" as REJECTED
    state "EXECUTING" as EXECUTING
    state "EXECUTED" as EXECUTED
    state "FAILED" as FAILED
    state "EXPIRED" as EXPIRED
    REQUESTED --> APPROVED
    REQUESTED --> REJECTED
    APPROVED --> EXECUTING
    EXECUTING --> EXECUTED
    EXECUTING --> FAILED
    APPROVED --> EXPIRED
```

## BusinessDayStatus

```mermaid
stateDiagram-v2
    state "OPEN" as OPEN
    state "CLOSING" as CLOSING
    state "CLOSED" as CLOSED
    state "REOPENED_REVIEW" as REOPENED_REVIEW
    OPEN --> CLOSING
    CLOSING --> CLOSED
    CLOSING --> OPEN
    CLOSED --> REOPENED_REVIEW
    REOPENED_REVIEW --> CLOSED
```

## DepositStatus

```mermaid
stateDiagram-v2
    state "NOT_APPLICABLE" as NOT_APPLICABLE
```

## DisputeStatus

```mermaid
stateDiagram-v2
    state "OPEN" as OPEN
    state "REVIEWING" as REVIEWING
    state "RESOLVED" as RESOLVED
    state "APPEALED" as APPEALED
    state "CLOSED" as CLOSED
    OPEN --> REVIEWING
    REVIEWING --> RESOLVED
    RESOLVED --> APPEALED
    APPEALED --> REVIEWING
    RESOLVED --> CLOSED
```

## ExportStatus

```mermaid
stateDiagram-v2
    state "REQUESTED" as REQUESTED
    state "APPROVED" as APPROVED
    state "RUNNING" as RUNNING
    state "READY" as READY
    state "REJECTED" as REJECTED
    state "EXPIRED" as EXPIRED
```

## FileStatus

```mermaid
stateDiagram-v2
    state "QUARANTINED" as QUARANTINED
    state "READY" as READY
    state "REJECTED" as REJECTED
    state "DELETED" as DELETED
```

## FulfillmentStatus

```mermaid
stateDiagram-v2
    state "NOT_READY" as NOT_READY
    state "READY" as READY
    state "FULFILLED" as FULFILLED
    state "VOID" as VOID
    NOT_READY --> READY
    READY --> FULFILLED
    READY --> VOID
```

## IdempotencyStatus

```mermaid
stateDiagram-v2
    state "PROCESSING" as PROCESSING
    state "COMPLETED" as COMPLETED
    state "FAILED" as FAILED
```

## InboxStatus

```mermaid
stateDiagram-v2
    state "RECEIVED" as RECEIVED
    state "APPLIED" as APPLIED
    state "EXCEPTION" as EXCEPTION
```

## MerchantApplicationStatus

```mermaid
stateDiagram-v2
    state "DRAFT" as DRAFT
    state "SUBMITTED" as SUBMITTED
    state "NEEDS_INFO" as NEEDS_INFO
    state "APPROVED" as APPROVED
    state "REJECTED" as REJECTED
```

## MerchantStatus

```mermaid
stateDiagram-v2
    state "DRAFT" as DRAFT
    state "APPROVED" as APPROVED
    state "SUSPENDED" as SUSPENDED
    state "EXITING" as EXITING
    state "EXITED" as EXITED
```

## OfflineReviewStatus

```mermaid
stateDiagram-v2
    state "SUBMITTED" as SUBMITTED
    state "VERIFIED" as VERIFIED
    state "REJECTED" as REJECTED
```

## OrderStatus

```mermaid
stateDiagram-v2
    state "DRAFT" as DRAFT
    state "PUBLISHED" as PUBLISHED
    state "CONFIRMED" as CONFIRMED
    state "FULFILLED" as FULFILLED
    state "CANCELLED" as CANCELLED
    state "EXPIRED" as EXPIRED
    DRAFT --> PUBLISHED
    PUBLISHED --> CONFIRMED
    PUBLISHED --> CANCELLED
    PUBLISHED --> EXPIRED
    CONFIRMED --> FULFILLED
    CONFIRMED --> CANCELLED
    CONFIRMED --> EXPIRED
```

## OutboxStatus

```mermaid
stateDiagram-v2
    state "PENDING" as PENDING
    state "SENT" as SENT
    state "DEAD" as DEAD
```

## ReceivableStatus

```mermaid
stateDiagram-v2
    state "OPEN" as OPEN
    state "PARTIAL" as PARTIAL
    state "SETTLED" as SETTLED
    OPEN --> PARTIAL
    OPEN --> SETTLED
    PARTIAL --> SETTLED
```

## ReconDifferenceStatus

```mermaid
stateDiagram-v2
    state "OPEN" as OPEN
    state "ASSIGNED" as ASSIGNED
    state "RESOLVED" as RESOLVED
    state "CLOSED" as CLOSED
```

## ReconciliationStatus

```mermaid
stateDiagram-v2
    state "CREATED" as CREATED
    state "RUNNING" as RUNNING
    state "MATCHED" as MATCHED
    state "DIFFERENCE" as DIFFERENCE
    state "RESOLVED" as RESOLVED
    state "FAILED" as FAILED
    CREATED --> RUNNING
    RUNNING --> MATCHED
    RUNNING --> DIFFERENCE
    RUNNING --> FAILED
    DIFFERENCE --> RESOLVED
```

## RefundStatus

```mermaid
stateDiagram-v2
    state "REQUESTED" as REQUESTED
    state "APPROVED" as APPROVED
    state "REJECTED" as REJECTED
    state "PROCESSING" as PROCESSING
    state "SUCCEEDED" as SUCCEEDED
    state "FAILED" as FAILED
    state "EXCEPTION" as EXCEPTION
    REQUESTED --> APPROVED
    REQUESTED --> REJECTED
    APPROVED --> PROCESSING
    APPROVED --> SUCCEEDED
    PROCESSING --> SUCCEEDED
    PROCESSING --> FAILED
    PROCESSING --> EXCEPTION
    FAILED --> PROCESSING
    EXCEPTION --> PROCESSING
```

## RepaymentStatus

```mermaid
stateDiagram-v2
    state "CREATED" as CREATED
    state "PENDING" as PENDING
    state "CONFIRMED" as CONFIRMED
    state "FAILED" as FAILED
    state "CLOSED" as CLOSED
    state "EXCEPTION" as EXCEPTION
    CREATED --> PENDING
    PENDING --> CONFIRMED
    PENDING --> FAILED
    PENDING --> CLOSED
    CLOSED --> EXCEPTION
    FAILED --> EXCEPTION
    EXCEPTION --> CONFIRMED
```

## ReservationStatus

```mermaid
stateDiagram-v2
    state "HELD" as HELD
    state "CONSUMED" as CONSUMED
    state "RELEASED" as RELEASED
    state "EXPIRED" as EXPIRED
    HELD --> CONSUMED
    HELD --> RELEASED
    HELD --> EXPIRED
```

## RuleVersionStatus

```mermaid
stateDiagram-v2
    state "DRAFT" as DRAFT
    state "APPROVED" as APPROVED
    state "RETIRED" as RETIRED
```

## SettlementStatus

```mermaid
stateDiagram-v2
    state "NOT_APPLICABLE" as NOT_APPLICABLE
```

## UserStatus

```mermaid
stateDiagram-v2
    state "ACTIVE" as ACTIVE
    state "SUSPENDED" as SUSPENDED
    state "CLOSED" as CLOSED
```
