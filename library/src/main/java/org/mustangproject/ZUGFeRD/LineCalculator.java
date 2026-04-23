package org.mustangproject.ZUGFeRD;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.math.RoundingMode;

/***
 * the linecalculator does the math within an item line, and e.g. calculates quantity*price.
 * @see TransactionCalculator
 */
public class LineCalculator {
	private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

	protected BigDecimal price;
	protected BigDecimal priceGross;
	protected BigDecimal itemTotalNetAmount;
	protected BigDecimal itemTotalVATAmount;
	protected BigDecimal lineAllowance = BigDecimal.ZERO;
	protected BigDecimal lineCharge = BigDecimal.ZERO;
	protected BigDecimal itemAllowance = BigDecimal.ZERO;
	protected BigDecimal itemCharge = BigDecimal.ZERO;
	protected BigDecimal allowanceItemTotal = BigDecimal.ZERO;
	/**
	 * When true, list price was taken as VAT-inclusive ({@link IZUGFeRDExportableItem#getGrossPrice()}),
	 * percentage allowances/charges were applied per unit with HALF_UP scale 2 on the running gross,
	 * then net ex VAT was derived per unit — matching common POS rounding and PEPPOL gross/net price
	 * consistency when exporting {@link org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider}.
	 */
	@JsonIgnore
	protected boolean grossInclusivePerUnitRounding = false;

	public LineCalculator(IZUGFeRDExportableItem currentItem) {

		BigDecimal vatPercent = null;
		if (currentItem.getProduct() != null) {
			vatPercent = currentItem.getProduct().getVATPercent();
		}
		if (vatPercent == null) {
			vatPercent = BigDecimal.ZERO;
		}
		BigDecimal multiplicator = vatPercent.divide(BigDecimal.valueOf(100));

		BigDecimal quantity = BigDecimal.ZERO;
		if ((currentItem != null) && (currentItem.getQuantity() != null)) {
			quantity = currentItem.getQuantity();
		}

		BigDecimal basisQuantity = currentItem.getBasisQuantity().compareTo(BigDecimal.ZERO) == 0
			? BigDecimal.ONE.setScale(4)
			: currentItem.getBasisQuantity();

		if (eligibleForGrossInclusivePerUnitRounding(currentItem, vatPercent)) {
			computeGrossInclusivePerUnitRounding(currentItem, vatPercent, quantity, basisQuantity);
			return;
		}

		if (currentItem.getItemAllowances() != null) {
			for (IZUGFeRDAllowanceCharge allowance : currentItem.getItemAllowances()) {
				BigDecimal singleAllowance = allowance.getTotalAmount(currentItem);
				addItemAllowance(singleAllowance);
				addAllowanceItemTotal(singleAllowance);

			}
		}
		if (currentItem.getItemCharges() != null) {
			for (IZUGFeRDAllowanceCharge charge : currentItem.getItemCharges()) {
				BigDecimal singleCharge = charge.getTotalAmount(currentItem);
				addItemCharge(singleCharge);
				subtractAllowanceItemTotal(singleCharge);

			}
		}
		if (currentItem.getItemTotalAllowances() != null) {
			for (final IZUGFeRDAllowanceCharge itemTotalAllowance : currentItem.getItemTotalAllowances()) {
				addAllowanceItemTotal(itemTotalAllowance.getTotalAmount(currentItem));
			}
		}

		price = currentItem.getPrice();
		priceGross = price;
//		price=price.subtract(itemAllowance).add(itemCharge);
//		BigDecimal delta=charge.subtract(allowanceItemTotal).subtract(allowance);
//		delta=delta.divide(currentItem.getQuantity(), 18, RoundingMode.HALF_UP);

		BigDecimal delta = BigDecimal.ZERO;
		if (currentItem.getProduct() != null) {
			if (currentItem.getProduct().getAllowances() != null) {
				for (IZUGFeRDAllowanceCharge ccaf : currentItem.getProduct().getAllowances()) {
					delta = delta.subtract(ccaf.getTotalAmount(currentItem));
				}
			}
			if (currentItem.getProduct().getCharges() != null) {
				for (IZUGFeRDAllowanceCharge ccaf : currentItem.getProduct().getCharges()) {
					delta = delta.add(ccaf.getTotalAmount(currentItem));
				}
			}
		}

		price = price.add(delta);
		// Division/Zero occurred here.
		// Used the setScale only because that's also done in getBasisQuantity
		itemTotalNetAmount = quantity.multiply(price).divide(basisQuantity, 18, RoundingMode.HALF_UP)
			.add(lineCharge).subtract(lineAllowance).subtract(allowanceItemTotal.setScale(2, RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
		if (currentItem.getGrossPrice() != null) {
			BigDecimal itemTotalGrossAmount = quantity.multiply(currentItem.getGrossPrice()).divide(basisQuantity, 18, RoundingMode.HALF_UP)
				.setScale(2, RoundingMode.HALF_UP);
			itemTotalVATAmount = itemTotalGrossAmount.subtract(itemTotalNetAmount).setScale(2, RoundingMode.HALF_UP);
		} else {
			itemTotalVATAmount = itemTotalNetAmount.multiply(multiplicator);//.setScale(2, RoundingMode.HALF_UP);
		}
	}

	private static boolean eligibleForGrossInclusivePerUnitRounding(IZUGFeRDExportableItem currentItem, BigDecimal vatPercent) {
		if (currentItem.getGrossPrice() == null) {
			return false;
		}
		if (vatPercent.compareTo(BigDecimal.ZERO) <= 0) {
			return false;
		}
		if (hasNonPercentLinePriceAdjustments(currentItem)) {
			return false;
		}
		return hasPercentAllowanceOrChargeOnLine(currentItem);
	}

	private static boolean hasNonPercentLinePriceAdjustments(IZUGFeRDExportableItem currentItem) {
		if (currentItem.getItemTotalAllowances() != null) {
			for (IZUGFeRDAllowanceCharge a : currentItem.getItemTotalAllowances()) {
				if (a.getPercent() == null) {
					return true;
				}
			}
		}
		if (currentItem.getItemAllowances() != null) {
			for (IZUGFeRDAllowanceCharge a : currentItem.getItemAllowances()) {
				if (a.getPercent() == null) {
					return true;
				}
			}
		}
		if (currentItem.getItemCharges() != null) {
			for (IZUGFeRDAllowanceCharge c : currentItem.getItemCharges()) {
				if (c.getPercent() == null) {
					return true;
				}
			}
		}
		if (currentItem.getProduct() != null) {
			if (currentItem.getProduct().getAllowances() != null) {
				for (IZUGFeRDAllowanceCharge a : currentItem.getProduct().getAllowances()) {
					if (a.getPercent() == null) {
						return true;
					}
				}
			}
			if (currentItem.getProduct().getCharges() != null) {
				for (IZUGFeRDAllowanceCharge c : currentItem.getProduct().getCharges()) {
					if (c.getPercent() == null) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean hasPercentAllowanceOrChargeOnLine(IZUGFeRDExportableItem currentItem) {
		if (currentItem.getItemAllowances() != null) {
			for (IZUGFeRDAllowanceCharge a : currentItem.getItemAllowances()) {
				if (a.getPercent() != null) {
					return true;
				}
			}
		}
		if (currentItem.getItemCharges() != null) {
			for (IZUGFeRDAllowanceCharge c : currentItem.getItemCharges()) {
				if (c.getPercent() != null) {
					return true;
				}
			}
		}
		if (currentItem.getProduct() != null) {
			if (currentItem.getProduct().getAllowances() != null) {
				for (IZUGFeRDAllowanceCharge a : currentItem.getProduct().getAllowances()) {
					if (a.getPercent() != null) {
						return true;
					}
				}
			}
			if (currentItem.getProduct().getCharges() != null) {
				for (IZUGFeRDAllowanceCharge c : currentItem.getProduct().getCharges()) {
					if (c.getPercent() != null) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private void computeGrossInclusivePerUnitRounding(IZUGFeRDExportableItem currentItem, BigDecimal vatPercent,
	                                                  BigDecimal quantity, BigDecimal basisQuantity) {
		grossInclusivePerUnitRounding = true;
		BigDecimal vatFactor = BigDecimal.ONE.add(vatPercent.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
		BigDecimal listGrossInclVAT = currentItem.getGrossPrice();
		BigDecimal runningGrossInclVAT = listGrossInclVAT;

		if (currentItem.getProduct() != null && currentItem.getProduct().getAllowances() != null) {
			for (IZUGFeRDAllowanceCharge allowance : currentItem.getProduct().getAllowances()) {
				if (allowance.getPercent() != null) {
					BigDecimal step = runningGrossInclVAT.multiply(allowance.getPercent()).divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP)
						.setScale(2, RoundingMode.HALF_UP);
					runningGrossInclVAT = runningGrossInclVAT.subtract(step);
				}
			}
		}
		if (currentItem.getProduct() != null && currentItem.getProduct().getCharges() != null) {
			for (IZUGFeRDAllowanceCharge charge : currentItem.getProduct().getCharges()) {
				if (charge.getPercent() != null) {
					BigDecimal step = runningGrossInclVAT.multiply(charge.getPercent()).divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP)
						.setScale(2, RoundingMode.HALF_UP);
					runningGrossInclVAT = runningGrossInclVAT.add(step);
				}
			}
		}
		if (currentItem.getItemAllowances() != null) {
			for (IZUGFeRDAllowanceCharge allowance : currentItem.getItemAllowances()) {
				if (allowance.getPercent() != null) {
					BigDecimal step = runningGrossInclVAT.multiply(allowance.getPercent()).divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP)
						.setScale(2, RoundingMode.HALF_UP);
					runningGrossInclVAT = runningGrossInclVAT.subtract(step);
				}
			}
		}
		if (currentItem.getItemCharges() != null) {
			for (IZUGFeRDAllowanceCharge charge : currentItem.getItemCharges()) {
				if (charge.getPercent() != null) {
					BigDecimal step = runningGrossInclVAT.multiply(charge.getPercent()).divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP)
						.setScale(2, RoundingMode.HALF_UP);
					runningGrossInclVAT = runningGrossInclVAT.add(step);
				}
			}
		}

		BigDecimal unitNetExVAT = runningGrossInclVAT.divide(vatFactor, 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
		BigDecimal effQty = quantity.divide(basisQuantity, 10, RoundingMode.HALF_UP);

		itemTotalNetAmount = unitNetExVAT.multiply(effQty).setScale(2, RoundingMode.HALF_UP);
		BigDecimal lineGrossInclVATAfter = runningGrossInclVAT.multiply(effQty).setScale(2, RoundingMode.HALF_UP);
		itemTotalVATAmount = lineGrossInclVATAfter.subtract(itemTotalNetAmount).setScale(2, RoundingMode.HALF_UP);

		price = unitNetExVAT;
		priceGross = listGrossInclVAT.divide(vatFactor, 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
		allowanceItemTotal = BigDecimal.ZERO;
	}

	public boolean isGrossInclusivePerUnitRounding() {
		return grossInclusivePerUnitRounding;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public BigDecimal getItemTotalNetAmount() {
		return itemTotalNetAmount;
	}

	public BigDecimal getItemTotalVATAmount() {
		return itemTotalVATAmount;
	}

	public BigDecimal getItemTotalGrossAmount() {
		return itemTotalNetAmount;
	}

	public BigDecimal getPriceGross() {
		return priceGross;
	}

	public void addLineAllowance(BigDecimal b) {
		lineAllowance = lineAllowance.add(b);
	}

	public void addLineCharge(BigDecimal b) {
		lineCharge = lineCharge.add(b);
	}

	public void addItemAllowance(BigDecimal b) {
		itemAllowance = itemAllowance.add(b);
	}

	public void addItemCharge(BigDecimal b) {
		itemCharge = itemCharge.add(b);
	}

	public void addAllowanceItemTotal(BigDecimal b) {
		allowanceItemTotal = allowanceItemTotal.add(b);
	}

	public void subtractAllowanceItemTotal(BigDecimal b) {
		allowanceItemTotal = allowanceItemTotal.subtract(b);
	}

}
