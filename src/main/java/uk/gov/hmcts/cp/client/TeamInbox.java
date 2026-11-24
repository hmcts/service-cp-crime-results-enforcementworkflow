package uk.gov.hmcts.cp.client;

/**
 * The resolved team inbox for a Division Code (feature 005, contracts/enforcement-area.md - OPEN DEPENDENCY, not
 * yet confirmed, plan.md D1). {@code divisionCode} is an echo only - never trusted back as authoritative; {@code area}
 * is always computed locally from the Confiscation Division Code set (FR-003), never from this response.
 */
public record TeamInbox(String teamEmail) {
}
