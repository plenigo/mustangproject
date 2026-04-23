package org.mustangproject.ZUGFeRD;

import org.junit.Test;
import org.mustangproject.Allowance;
import org.mustangproject.BankDetails;
import org.mustangproject.Charge;
import org.mustangproject.Contact;
import org.mustangproject.Invoice;
import org.mustangproject.Item;
import org.mustangproject.Product;
import org.mustangproject.TradeParty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import static java.math.BigDecimal.TEN;
import static java.math.BigDecimal.valueOf;
import static org.xmlunit.assertj.XmlAssert.assertThat;

/***
 * tests the linecalculator and transactioncalculator classes
 *
 */
public class CalculationTest extends ResourceCase {
	private static final Logger LOGGER = LoggerFactory.getLogger(CalculationTest.class);

	@Test
	public void testLineCalculator_simpleAmounts_resultInValidVATAmount() {
		final IZUGFeRDExportableProduct product = new IZUGFeRDExportableProductImpl().setVatPercent(valueOf(16));
		final IZUGFeRDExportableItem currentItem = new IZUGFeRDExportableItemImpl().setPrice(valueOf(100))
			.setQuantity(TEN)
			.setProduct(product);

		final LineCalculator calculator = currentItem.getCalculation();

		assertEquals(valueOf(100).stripTrailingZeros(), calculator.getPrice().stripTrailingZeros());
		assertEquals(valueOf(1000).stripTrailingZeros(), calculator.getItemTotalNetAmount().stripTrailingZeros());
		assertEquals(valueOf(160).stripTrailingZeros(), calculator.getItemTotalVATAmount().stripTrailingZeros());
	}

	@Test
	public void testLineCalculatorInclusiveAllowance() {
		//This test failed with previous implementation. By rounding the totalVATAmount to 2 decimal places the result became wrong
		final IZUGFeRDExportableProduct product = new IZUGFeRDExportableProductImpl().setVatPercent(valueOf(16));
		// 10 % discount on each item
		final IZUGFeRDAllowanceCharge allowance = new IZUGFeRDAllowanceChargeImpl().setTotalAmount(valueOf(14.8730));

		final IZUGFeRDExportableItem currentItem = new IZUGFeRDExportableItemImpl().setPrice(valueOf(148.73))
			.setQuantity(valueOf(12))
			.setItemAllowances(new IZUGFeRDAllowanceCharge[]{allowance})
			.setProduct(product);

		final LineCalculator calculator = currentItem.getCalculation();

		assertEquals(valueOf(148.73).stripTrailingZeros(), calculator.getPrice().stripTrailingZeros());
		assertEquals(valueOf(1769.89).stripTrailingZeros(), calculator.getItemTotalNetAmount().stripTrailingZeros());
		assertEquals(valueOf(283.1824).stripTrailingZeros(), calculator.getItemTotalVATAmount().stripTrailingZeros());
	}

	@Test
	public void testLineCalculatorInclusiveAllowanceAndCharge() {
		final IZUGFeRDExportableProduct product = new IZUGFeRDExportableProductImpl().setVatPercent(valueOf(16));
		// 10 % discount on each item
		final IZUGFeRDAllowanceCharge allowance = new IZUGFeRDAllowanceChargeImpl().setTotalAmount(valueOf(14.873));
		// 20 % charge
		final IZUGFeRDAllowanceCharge charge = new IZUGFeRDAllowanceChargeImpl().setTotalAmount(valueOf(29.746));
		final IZUGFeRDExportableItem currentItem = new IZUGFeRDExportableItemImpl().setPrice(valueOf(148.73))
			.setQuantity(valueOf(12))
			.setItemAllowances(new IZUGFeRDAllowanceCharge[]{allowance})
			.setItemCharges(new IZUGFeRDAllowanceCharge[]{charge})
			.setProduct(product);

		final LineCalculator calculator = currentItem.getCalculation();

		assertEquals(valueOf(148.73).stripTrailingZeros(), calculator.getPrice().stripTrailingZeros());
		assertEquals(valueOf(1799.63).stripTrailingZeros(), calculator.getItemTotalNetAmount().stripTrailingZeros());
		assertEquals(valueOf(287.9408).stripTrailingZeros(), calculator.getItemTotalVATAmount().stripTrailingZeros());
	}

	@Test
	public void testAllowanceAndChargeEx4() {
		/** numbers from en16931 example 4 */
		SimpleDateFormat sqlDate = new SimpleDateFormat("yyyy-MM-dd");

		Invoice invoice = new Invoice();
		invoice.setDocumentName("Rechnung");
		invoice.setNumber("777777");
		try {
			invoice.setIssueDate(sqlDate.parse("2020-12-31"));
		} catch (Exception e) {
			LOGGER.error("Failed to set dates", e);

		}

		/* trade party (sender) */
		TradeParty sender = new TradeParty("Maier GmbH", "Musterweg 5", "11111", "Testung", "DE");
		sender.addVATID("DE2222222222");
		invoice.setSender(sender);

		/* trade party (recipient) */
		TradeParty recipient = new TradeParty("Teston GmbH" + " " + "Zentrale" + " " + "", "Testweg 5", "11111", "Testung", "DE");
		invoice.setRecipient(recipient);

		/* item */
		Product product;
		Item item;

		product = new Product("Pens", "", "H87", new BigDecimal(25));
		product.addAllowance(new Allowance(new BigDecimal(1)));
		item = new Item(product, new BigDecimal("9.50"), new BigDecimal(25));
		item.addCharge(new Charge(new BigDecimal(10)).setReasonCode("ZZZ").setReason("Zuschlag"));
		LineCalculator lc = item.getCalculation();
		assertEquals(new BigDecimal("222.50"), lc.getItemTotalNetAmount());
		invoice.addItem(item);
		product = new Product("Paper", "", "H87", new BigDecimal(25));
		item = new Item(product, new BigDecimal("4.50"), new BigDecimal(15));
		item.addAllowance(new Allowance().setPercent(new BigDecimal(5)).setReasonCode("ZZZ").setReason("Zuschlag"));
		lc = item.getCalculation();
		assertEquals(new BigDecimal("64.12"), lc.getItemTotalNetAmount());
		invoice.addItem(item);
		invoice.addAllowance(new Allowance().setPercent(new BigDecimal(10)).setTaxPercent(new BigDecimal(25)).setReasonCode("ZZZ").setReason("Mengenrabatt"));
		invoice.addCharge(new Charge(new BigDecimal(15)).setReasonCode("ZZZ").setReason("Frachtkosten"));

		TransactionCalculator calculator = new TransactionCalculator(invoice);
		assertEquals(valueOf(286.62).stripTrailingZeros(), calculator.getTotal());// interestingly, EN16931-1 has 286.63 here?
		assertEquals(valueOf(272.96).stripTrailingZeros(), calculator.getTaxBasis()); // and 272.97 here
		assertEquals(valueOf(337.45).stripTrailingZeros(), calculator.getDuePayable()); // and 337.46 here???
	}

	@Test
	public void testLineCalculatorForeignCurrencyExample() {
/*** xml of official fx sample with allowances and charges
 *  10x100 with 10% and 50€ item discount =850€
 *  +8,75 charges on document level=858,75, +19%VAT=1021,91
 *  prepaid 500->due payable=521,91
 */
		File inputCII = getResourceAsFile("Extended_fremdwaehrung.xml");

		ZUGFeRDInvoiceImporter zii = new ZUGFeRDInvoiceImporter();
		Invoice invoice = null;
		zii.doIgnoreCalculationErrors();
		boolean hasExceptions = false;
		try {
			zii.setInputStream(new FileInputStream(inputCII));

			invoice = zii.extractInvoice();
		} catch (XPathExpressionException | ParseException e) {
// handle Exceptions
			hasExceptions = true;
		} catch (FileNotFoundException e) {
			hasExceptions = true;
		}
		assertFalse(hasExceptions);
		// Reading ZUGFeRD

		final TransactionCalculator calculator = new TransactionCalculator(invoice);

		assertEquals(valueOf(521.91).stripTrailingZeros(), calculator.getDuePayable().stripTrailingZeros());


	}


	@Test
	public void testTotalCalculatorGrandTotalRounding() {
		SimpleDateFormat sqlDate = new SimpleDateFormat("yyyy-MM-dd");

		BigDecimal sales_tax_percent1 = new BigDecimal(16);
		BigDecimal total_increase_percent = new BigDecimal(0.80);
		BigDecimal total_discount_percent = new BigDecimal(2.00);


		/* invoice (1st part) */

		Invoice invoice = new Invoice();
		invoice.setDocumentName("Rechnung");
		invoice.setNumber("777777");
		try {
			invoice.setIssueDate(sqlDate.parse("2020-12-31"));
			invoice.setDetailedDeliveryPeriod(sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[0]), sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[1]));
			invoice.setDeliveryDate(sqlDate.parse("2020-12-31"));
			invoice.setDueDate(sqlDate.parse("2021-01-15"));
		} catch (Exception e) {
			LOGGER.error("Failed to set dates", e);

		}

		/* trade party (sender) */
		TradeParty sender = new TradeParty("Maier GmbH", "Musterweg 5", "11111", "Testung", "DE");
		sender.addVATID("DE2222222222");
		invoice.setSender(sender);

		/* trade party (recipient) */
		TradeParty recipient = new TradeParty("Teston GmbH" + " " + "Zentrale" + " " + "", "Testweg 5", "11111", "Testung", "DE");
		recipient.setID("111111");
		recipient.addVATID("DE111111111");
		invoice.setRecipient(recipient);

		/* item */
		Product product;
		Item item;

		product = new Product("AAA", "", "H87", sales_tax_percent1).setSellerAssignedID("1AAA");
		item = new Item(product, new BigDecimal("4.750"), new BigDecimal(5.00));

		// set values for additional charge and discount used for next lines
		BigDecimal item_increase = BigDecimal.ZERO;
		BigDecimal item_discount = BigDecimal.valueOf(10.00);

		/* lines */
		if (item_increase.compareTo(BigDecimal.ZERO) > 0) {
			item.addCharge(new Charge().setPercent(item_increase).setTaxPercent(sales_tax_percent1).setReasonCode("ZZZ").setReason("Zuschlag"));
		}
		if (item_discount.compareTo(BigDecimal.ZERO) > 0) {
			item.addAllowance(new Allowance().setPercent(item_discount).setTaxPercent(sales_tax_percent1).setReasonCode("95").setReason("Rabatt"));
		}
		invoice.addItem(item);


		product = new Product("BBB", "", "H87", sales_tax_percent1).setSellerAssignedID("2BBB");
		item = new Item(product, new BigDecimal("5.750"), new BigDecimal(4.00));
		invoice.addItem(item);

		product = new Product("CCC", "", "H87", sales_tax_percent1).setSellerAssignedID("3CCC");
		item = new Item(product, new BigDecimal("6.750"), new BigDecimal(3.00));
		invoice.addItem(item);

		product = new Product("DDD", "", "H87", sales_tax_percent1).setSellerAssignedID("4DDD");
		item = new Item(product, new BigDecimal("7.750"), new BigDecimal(2.00));
		invoice.addItem(item);

		product = new Product("EEE", "", "H87", sales_tax_percent1).setSellerAssignedID("5EEE");
		item = new Item(product, new BigDecimal("8.750"), new BigDecimal(1.00));
		invoice.addItem(item);


		if (total_increase_percent.compareTo(BigDecimal.ZERO) > 0) {
			invoice.addCharge(new Charge().setPercent(total_increase_percent).setTaxPercent(sales_tax_percent1).setReasonCode("ZZZ").setReason("Zuschläge"));
		}
		if (total_discount_percent.compareTo(BigDecimal.ZERO) > 0) {
			invoice.addAllowance(new Allowance().setPercent(total_discount_percent).setTaxPercent(sales_tax_percent1).setReasonCode("95").setReason("Rabatte"));
		}
		TransactionCalculator calculator = new TransactionCalculator(invoice);
		assertEquals(valueOf(101.85).stripTrailingZeros(), calculator.getGrandTotal().stripTrailingZeros());
	}

	@Test
	public void testNullifyingAllowancesCharges() {
		SimpleDateFormat sqlDate = new SimpleDateFormat("yyyy-MM-dd");


		Invoice invoice = new Invoice();
		invoice.setDocumentName("Rechnung");
		invoice.setNumber("777777");
		try {
			invoice.setIssueDate(sqlDate.parse("2020-12-31"));
			invoice.setDetailedDeliveryPeriod(sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[0]), sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[1]));
			invoice.setDeliveryDate(sqlDate.parse("2020-12-31"));
			invoice.setDueDate(sqlDate.parse("2021-01-15"));
		} catch (Exception e) {
			LOGGER.error("Failed to set dates", e);

		}

		/* trade party (sender) */
		TradeParty sender = new TradeParty("Maier GmbH", "Musterweg 5", "11111", "Testung", "DE");
		sender.addVATID("DE2222222222");
		invoice.setSender(sender);

		/* trade party (recipient) */
		TradeParty recipient = new TradeParty("Teston GmbH" + " " + "Zentrale" + " " + "", "Testweg 5", "11111", "Testung", "DE");
		recipient.setID("111111");
		recipient.addVATID("DE111111111");
		invoice.setRecipient(recipient);

		/* item */
		Product product;
		Item item;
		BigDecimal amount = new BigDecimal("10.00");

		product = new Product("AAA", "", "H87", BigDecimal.ZERO).setSellerAssignedID("1AAA");
		product.addCharge(new Charge(amount).setReasonCode("ZZZ").setReason("Zuschlag"));
		product.addAllowance((Allowance) new Allowance(amount).setReasonCode("95").setReason("Rabatt"));
		item = new Item(product, new BigDecimal("4.750"), new BigDecimal(1.00));

		// set values for additional charge and discount used for next lines
		item.addCharge(new Charge(amount).setReasonCode("ZZZ").setReason("Zuschlag"));
		item.addAllowance((Allowance) new Allowance(amount).setReasonCode("95").setReason("Rabatt"));
		invoice.addItem(item);

		TransactionCalculator calculator = new TransactionCalculator(invoice);
		assertEquals(valueOf(4.750).stripTrailingZeros(), calculator.getGrandTotal().stripTrailingZeros());
	}

	public void testSimpleItemPercentAllowance() {
		/***
		 * a product with net 1.10 and qty 5 and relative item allowance of 10% should return 5 as line and grand total
		 */
		SimpleDateFormat sqlDate = new SimpleDateFormat("yyyy-MM-dd");

		Invoice invoice = new Invoice();
		invoice.setDocumentName("Rechnung");
		invoice.setNumber("777777");
		try {
			invoice.setIssueDate(sqlDate.parse("2020-12-31"));
			invoice.setDetailedDeliveryPeriod(sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[0]), sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[1]));
			invoice.setDeliveryDate(sqlDate.parse("2020-12-31"));
			invoice.setDueDate(sqlDate.parse("2021-01-15"));
		} catch (Exception e) {
			LOGGER.error("Failed to set dates", e);

		}
		TradeParty sender = new TradeParty("Maier GmbH", "Musterweg 5", "11111", "Testung", "DE");
		sender.addVATID("DE2222222222");
		invoice.setSender(sender);

		/* trade party (recipient) */
		TradeParty recipient = new TradeParty("Teston GmbH" + " " + "Zentrale" + " " + "", "Testweg 5", "11111", "Testung", "DE");
		recipient.setID("111111");
		recipient.addVATID("DE111111111");
		invoice.setRecipient(recipient);

		/* item */
		Product product;
		Item item;

		product = new Product("AAA", "", "H87", BigDecimal.ZERO);
		item = new Item(product, new BigDecimal("1.10"), new BigDecimal(5.00));

		item.addAllowance(new Allowance().setPercent(new BigDecimal(10)).setTaxPercent(BigDecimal.ZERO));
		invoice.addItem(item);


		ZUGFeRD2PullProvider zf2p = new ZUGFeRD2PullProvider();
		zf2p.setProfile(Profiles.getByName("XRechnung"));
		zf2p.generateXML(invoice);


		String theXML = new String(zf2p.getXML(), StandardCharsets.UTF_8);
		assertThat(theXML).valueByXPath("//*[local-name()='ActualAmount']")
			.asString()
			.isEqualTo("0.55");// test for issue #917


		TransactionCalculator calculator = new TransactionCalculator(invoice);
		assertEquals(new BigDecimal("4.95"), calculator.getGrandTotal().stripTrailingZeros());
	}

	public void testSimpleItemPercentCharge() {
		/***
		 * a product with net 1.10 and qty 5 and relative item allowance of 10% should return 5 as line and grand total
		 */
		SimpleDateFormat sqlDate = new SimpleDateFormat("yyyy-MM-dd");

		Invoice invoice = new Invoice();
		invoice.setDocumentName("Rechnung");
		invoice.setNumber("777777");
		try {
			invoice.setIssueDate(sqlDate.parse("2020-12-31"));
			invoice.setDetailedDeliveryPeriod(sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[0]), sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[1]));
			invoice.setDeliveryDate(sqlDate.parse("2020-12-31"));
			invoice.setDueDate(sqlDate.parse("2021-01-15"));
		} catch (Exception e) {
			LOGGER.error("Failed to set dates", e);

		}
		TradeParty sender = new TradeParty("Maier GmbH", "Musterweg 5", "11111", "Testung", "DE");
		sender.addVATID("DE2222222222");
		invoice.setSender(sender);

		/* trade party (recipient) */
		TradeParty recipient = new TradeParty("Teston GmbH" + " " + "Zentrale" + " " + "", "Testweg 5", "11111", "Testung", "DE");
		recipient.setID("111111");
		recipient.addVATID("DE111111111");
		invoice.setRecipient(recipient);

		/* item */
		Product product;
		Item item;

		product = new Product("AAA", "", "H87", BigDecimal.ZERO);
		item = new Item(product, new BigDecimal("1.10"), new BigDecimal(5.00));

		item.addCharge(new Charge().setPercent(new BigDecimal(10)).setTaxPercent(BigDecimal.ZERO));
		invoice.addItem(item);


		ZUGFeRD2PullProvider zf2p = new ZUGFeRD2PullProvider();
		zf2p.setProfile(Profiles.getByName("XRechnung"));
		zf2p.generateXML(invoice);


		String theXML = new String(zf2p.getXML(), StandardCharsets.UTF_8);
		assertThat(theXML).valueByXPath("//*[local-name()='ActualAmount']")
			.asString()
			.isEqualTo("0.55");// test for issue #917


		TransactionCalculator calculator = new TransactionCalculator(invoice);
		assertEquals(new BigDecimal("6.05"), calculator.getGrandTotal().stripTrailingZeros());
	}

	public void testSimpleDocumentPercentCharge() {

		String orgname = "Test company";
		String number = "123";
		String priceStr = "3.00";
		BigDecimal price = new BigDecimal(priceStr);


		// similar, but slightly less complicated to whats later testted in  testRelativeChargesAllowancesExport
		Invoice i = new Invoice().setCurrency("CHF").setDueDate(new Date()).setIssueDate(new Date()).setDeliveryDate(new Date())
			.setSender(new TradeParty(orgname, "teststr", "55232", "teststadt", "DE").addTaxID("4711").addVATID("DE0815"))
			.setRecipient(new TradeParty("Franz Müller", "teststr.12", "55232", "Entenhausen", "DE"))
			.setNumber(number)
			.addItem(new Item(new Product("Testprodukt", "", "H87", new BigDecimal(19)), price, new BigDecimal(1.0)))
			.addItem(new Item(new Product("Testprodukt", "", "H87", new BigDecimal(19)), price, new BigDecimal(1.0)))
			.addItem(new Item(new Product("Testprodukt", "", "H87", new BigDecimal(19)), price, new BigDecimal(1.0)))
			.addCharge(new Charge().setPercent(new BigDecimal(50)).setTaxPercent(new BigDecimal(19)).setReasonCode("ABK"));
		// 9+50%=>13,50 expected net
		//		.addAllowance(new Allowance().setPercent(new BigDecimal(50)).setTaxPercent(new BigDecimal(19)).setReason("Mengenrabatt"))
		TransactionCalculator tc = new TransactionCalculator(i);
		assertEquals(new BigDecimal("13.50"), tc.getTaxBasis());

		assertEquals(new BigDecimal("16.07"), tc.getDuePayable());
	}

	public void testSimpleDocumentPercentAllowance() {

		String orgname = "Test company";
		String number = "123";
		String priceStr = "3.00";
		BigDecimal price = new BigDecimal(priceStr);


		// similar, but slightly less complicated to whats later testted in  testRelativeChargesAllowancesExport
		Invoice i = new Invoice().setCurrency("CHF").setDueDate(new Date()).setIssueDate(new Date()).setDeliveryDate(new Date())
			.setSender(new TradeParty(orgname, "teststr", "55232", "teststadt", "DE").addTaxID("4711").addVATID("DE0815"))
			.setRecipient(new TradeParty("Franz Müller", "teststr.12", "55232", "Entenhausen", "DE"))
			.setNumber(number)
			.addItem(new Item(new Product("Testprodukt", "", "H87", new BigDecimal(19)), price, new BigDecimal(1.0)))
			.addItem(new Item(new Product("Testprodukt", "", "H87", new BigDecimal(19)), price, new BigDecimal(1.0)))
			.addItem(new Item(new Product("Testprodukt", "", "H87", new BigDecimal(19)), price, new BigDecimal(1.0)))
			.addAllowance(new Allowance().setPercent(new BigDecimal(50)).setTaxPercent(new BigDecimal(19)).setReasonCode("ABK"));
		// 9-50%=>4,50 expected net
		//		.addAllowance(new Allowance().setPercent(new BigDecimal(50)).setTaxPercent(new BigDecimal(19)).setReason("Mengenrabatt"))
		TransactionCalculator tc = new TransactionCalculator(i);
		assertEquals(new BigDecimal("4.50"), tc.getTaxBasis());

		assertEquals(new BigDecimal("5.36"), tc.getDuePayable());
	}

	public void testSimpleItemTotalAllowance() {
		/***
		 * a product with net 1 and qty 5 and absolute _item_ allowance of 1 should return 4 as line total, and grand total
		 */
		SimpleDateFormat sqlDate = new SimpleDateFormat("yyyy-MM-dd");

		Invoice invoice = new Invoice();
		invoice.setDocumentName("Rechnung");
		invoice.setNumber("777777");
		try {
			invoice.setIssueDate(sqlDate.parse("2020-12-31"));
			invoice.setDetailedDeliveryPeriod(sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[0]), sqlDate.parse("2020-12-01 - 2020-12-31".split(" - ")[1]));
			invoice.setDeliveryDate(sqlDate.parse("2020-12-31"));
			invoice.setDueDate(sqlDate.parse("2021-01-15"));
		} catch (Exception e) {
			LOGGER.error("Failed to set dates", e);

		}
		TradeParty sender = new TradeParty("Maier GmbH", "Musterweg 5", "11111", "Testung", "DE");
		sender.addVATID("DE2222222222");
		invoice.setSender(sender);

		/* trade party (recipient) */
		TradeParty recipient = new TradeParty("Teston GmbH" + " " + "Zentrale" + " " + "", "Testweg 5", "11111", "Testung", "DE");
		recipient.setID("111111");
		recipient.addVATID("DE111111111");
		invoice.setRecipient(recipient);

		/* item */
		Product product;
		Item item;

		product = new Product("AAA", "", "H87", BigDecimal.ZERO);
		item = new Item(product, new BigDecimal("1.00"), new BigDecimal(5.00));

		item.addAllowance(new Allowance(new BigDecimal(1)).setTaxPercent(BigDecimal.ZERO));
		invoice.addItem(item);

		TransactionCalculator calculator = new TransactionCalculator(invoice);
		assertEquals(new BigDecimal(4), calculator.getGrandTotal().stripTrailingZeros());
	}


	/**
	 * LineCalculator should not throw an exception when calculating a non-terminating decimal expansion
	 */
	@Test
	public void testNonTerminatingDecimalExpansion() {
		final Product product = new Product();
		final IZUGFeRDExportableItem currentItem = new Item().setPrice(valueOf(386.52))
			.setQuantity(BigDecimal.valueOf(31))
			.setBasisQuantity(BigDecimal.valueOf(366))
			.setProduct(product);
		final LineCalculator calculator = currentItem.getCalculation();
		assertEquals(BigDecimal.valueOf(32.74), calculator.getItemTotalNetAmount());
	}

	/**
	 * {@link Item#setGrossPrice(java.math.BigDecimal) Gross price} 4.99 € is the <strong>unit list price
	 * including VAT</strong> (here 7 %) <strong>before</strong> any discount — same meaning as
	 * {@link IZUGFeRDExportableItem#getGrossPrice()}: incl. taxes, excl. allowances/charges.
	 * The 13 % product allowance is then applied on that VAT-inclusive unit with per-step rounding;
	 * net ex VAT and CII gross/net (PEPPOL-EN16931-R046) follow from that pipeline.
	 * Full XRechnung/CII rules validation: {@code GrossInclusiveXRechnungCIIValidationTest} in the {@code validator} module
	 * (run e.g. {@code mvn test -pl validator -am -Dtest=...GrossInclusive... -Dsurefire.failIfNoSpecifiedTests=false}).
	 */
	@Test
	public void testGrossInclusiveVatPercentDiscountPerUnitXmlMatchesTotalsAndPeppolR046() throws Exception {
		SimpleDateFormat sqlDate = new SimpleDateFormat("yyyy-MM-dd");
		TradeParty recipient = new TradeParty("Buyer GmbH", "Käuferweg 2", "80331", "München", "DE");
		recipient.setID("991-01484-64");
		recipient.setEmail("buyer@example.org");
		recipient.addVATID("DE999999999");
		Product product = new Product("Artikel", "", "H87", new BigDecimal("7"));
		Allowance lineDiscount = new Allowance();
		lineDiscount.setPercent(new BigDecimal("13"));
		lineDiscount.setTaxPercent(new BigDecimal("7"));
		lineDiscount.setReasonCode("95");
		lineDiscount.setReason("Rabatt");
		product.addAllowance(lineDiscount);
		// 4.99 € = one unit, VAT-inclusive list price, no discount applied yet (discount is the 13 % allowance below).
		Item item = new Item(product, BigDecimal.ZERO, new BigDecimal("9")).setGrossPrice(new BigDecimal("4.99"));

		LineCalculator lc = item.getCalculation();
		assertEquals(0, new BigDecimal("36.54").compareTo(lc.getItemTotalNetAmount()));
		assertEquals(0, new BigDecimal("2.52").compareTo(lc.getItemTotalVATAmount()));
		assertEquals(0, new BigDecimal("4.06").compareTo(lc.getPrice()));
		assertEquals(0, new BigDecimal("4.66").compareTo(lc.getPriceGross()));

		Invoice invoice = new Invoice()
			.setDocumentName("Rechnung")
			.setCurrency("EUR")
			.setDueDate(sqlDate.parse("2026-05-23"))
			.setIssueDate(sqlDate.parse("2026-04-23"))
			.setDeliveryDate(sqlDate.parse("2026-04-23"))
			.setSender(new TradeParty("Seller GmbH", "Verkäuferstr. 1", "10115", "Berlin", "DE")
				.addTaxID("201/113/40209")
				.addVATID("DE115235681")
				.setEmail("seller@example.org")
				.setContact(new Contact("Hans Test", "+493012345678", "seller@example.org"))
				.addBankDetails(new BankDetails("DE12500105170648489890", "COBADEFXXX").setAccountName("Seller GmbH")))
			.setRecipient(recipient)
			.setReferenceNumber("991-01484-64")
			.setNumber("INV-R046-1")
			.addItem(item);

		TransactionCalculator tc = new TransactionCalculator(invoice);
		assertEquals(0, new BigDecimal("36.54").compareTo(tc.getTaxBasis()));
		assertEquals(0, new BigDecimal("39.06").compareTo(tc.getGrandTotal()));
		assertEquals(0, new BigDecimal("2.52").compareTo(tc.getGrandTotal().subtract(tc.getTaxBasis())));

		ZUGFeRD2PullProvider zf2p = new ZUGFeRD2PullProvider();
		zf2p.setProfile(Profiles.getByName("XRechnung"));
		zf2p.generateXML(invoice);
		String theXML = new String(zf2p.getXML(), StandardCharsets.UTF_8);
		// Schematron / EN16931 rules via ZUGFeRDValidator: same scenario in validator module
		// (org.mustangproject.validator.GrossInclusiveXRechnungCIIValidationTest) — not inlined here because a
		// test dependency library → validator would create a Maven reactor cycle (validator already depends on library).
		// Example:
		// ZUGFeRDValidator zfValidator = new ZUGFeRDValidator();
		// String result = zfValidator.validate(zf2p.getXML(), "xrechnung-" + invoice.getNumber() + ".xml");
		// assertThat(result).valueByXPath("/validation/xml/summary/@status").isEqualTo("valid");
		// assertThat(result).valueByXPath("/validation/summary/@status").isEqualTo("valid");

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(theXML.getBytes(StandardCharsets.UTF_8)));
		XPath xp = XPathFactory.newInstance().newXPath();
		String grossStr = xp.evaluate(
			"(//*[local-name()='IncludedSupplyChainTradeLineItem'])[1]//*[local-name()='GrossPriceProductTradePrice']//*[local-name()='ChargeAmount']/text()",
			doc).trim();
		String allowanceStr = xp.evaluate(
			"(//*[local-name()='IncludedSupplyChainTradeLineItem'])[1]//*[local-name()='GrossPriceProductTradePrice']//*[local-name()='AppliedTradeAllowanceCharge']//*[local-name()='ActualAmount']/text()",
			doc).trim();
		String netStr = xp.evaluate(
			"(//*[local-name()='IncludedSupplyChainTradeLineItem'])[1]//*[local-name()='NetPriceProductTradePrice']//*[local-name()='ChargeAmount']/text()",
			doc).trim();
		BigDecimal grossUnit = new BigDecimal(grossStr);
		BigDecimal allowanceUnit = new BigDecimal(allowanceStr);
		BigDecimal netUnit = new BigDecimal(netStr);
		assertEquals(0, grossUnit.subtract(allowanceUnit).compareTo(netUnit));

		assertThat(theXML).valueByXPath(
			"//*[local-name()='IncludedSupplyChainTradeLineItem'][1]//*[local-name()='SpecifiedTradeSettlementLineMonetarySummation']//*[local-name()='LineTotalAmount']").asString().isEqualTo("36.54");
		assertThat(theXML).valueByXPath(
			"//*[local-name()='ApplicableHeaderTradeSettlement']//*[local-name()='SpecifiedTradeSettlementHeaderMonetarySummation']//*[local-name()='TaxBasisTotalAmount']").asString().isEqualTo("36.54");
		assertThat(theXML).valueByXPath(
			"//*[local-name()='ApplicableHeaderTradeSettlement']//*[local-name()='SpecifiedTradeSettlementHeaderMonetarySummation']//*[local-name()='GrandTotalAmount']").asString().isEqualTo("39.06");
	}

	/**
	 * Same VAT-inclusive list unit (4.99 €) and quantity as the discount scenario, but <strong>no</strong> allowances:
	 * net unit ex VAT is the list price (4.66 €); VAT is derived as line gross incl. VAT minus line net (legacy path in
	 * {@link LineCalculator} when no percentage line adjustments exist). CII has no {@code GrossPriceProductTradePrice} block.
	 * Validator: {@code GrossInclusiveXRechnungCIIValidationTest#testGrossInclusiveNoDiscountXmlValidatesWithZugferdValidator}.
	 */
	@Test
	public void testGrossInclusiveNoDiscountXmlAndTotals() throws Exception {
		SimpleDateFormat sqlDate = new SimpleDateFormat("yyyy-MM-dd");
		TradeParty recipient = new TradeParty("Buyer GmbH", "Käuferweg 2", "80331", "München", "DE");
		recipient.setID("991-01484-64");
		recipient.setEmail("buyer@example.org");
		recipient.addVATID("DE999999999");
		Product product = new Product("Artikel ohne Rabatt", "", "H87", new BigDecimal("7"));
		Item item = new Item(product, new BigDecimal("4.66"), new BigDecimal("9")).setGrossPrice(new BigDecimal("4.99"));

		LineCalculator lc = item.getCalculation();
		assertEquals(0, new BigDecimal("4.66").compareTo(lc.getPrice()));
		assertEquals(0, new BigDecimal("4.66").compareTo(lc.getPriceGross()));
		assertEquals(0, new BigDecimal("41.94").compareTo(lc.getItemTotalNetAmount()));
		assertEquals(0, new BigDecimal("2.97").compareTo(lc.getItemTotalVATAmount()));

		Invoice invoice = new Invoice()
			.setDocumentName("Rechnung")
			.setCurrency("EUR")
			.setDueDate(sqlDate.parse("2026-05-23"))
			.setIssueDate(sqlDate.parse("2026-04-23"))
			.setDeliveryDate(sqlDate.parse("2026-04-23"))
			.setSender(new TradeParty("Seller GmbH", "Verkäuferstr. 1", "10115", "Berlin", "DE")
				.addTaxID("201/113/40209")
				.addVATID("DE115235681")
				.setEmail("seller@example.org")
				.setContact(new Contact("Hans Test", "+493012345678", "seller@example.org"))
				.addBankDetails(new BankDetails("DE12500105170648489890", "COBADEFXXX").setAccountName("Seller GmbH")))
			.setRecipient(recipient)
			.setReferenceNumber("991-01484-64")
			.setNumber("INV-NO-DISC-1")
			.addItem(item);

		TransactionCalculator tc = new TransactionCalculator(invoice);
		assertEquals(0, new BigDecimal("41.94").compareTo(tc.getTaxBasis()));
		assertEquals(0, new BigDecimal("44.91").compareTo(tc.getGrandTotal()));

		ZUGFeRD2PullProvider zf2p = new ZUGFeRD2PullProvider();
		zf2p.setProfile(Profiles.getByName("XRechnung"));
		zf2p.generateXML(invoice);
		String theXML = new String(zf2p.getXML(), StandardCharsets.UTF_8);

		assertThat(theXML).valueByXPath(
			"count(//*[local-name()='IncludedSupplyChainTradeLineItem'][1]//*[local-name()='GrossPriceProductTradePrice'])")
			.asInt()
			.isEqualTo(0);
		assertThat(theXML).valueByXPath(
			"//*[local-name()='IncludedSupplyChainTradeLineItem'][1]//*[local-name()='NetPriceProductTradePrice']//*[local-name()='ChargeAmount']").asDouble().isEqualTo(4.66);
		assertThat(theXML).valueByXPath(
			"//*[local-name()='IncludedSupplyChainTradeLineItem'][1]//*[local-name()='SpecifiedTradeSettlementLineMonetarySummation']//*[local-name()='LineTotalAmount']").asString().isEqualTo("41.94");
		assertThat(theXML).valueByXPath(
			"//*[local-name()='ApplicableHeaderTradeSettlement']//*[local-name()='SpecifiedTradeSettlementHeaderMonetarySummation']//*[local-name()='TaxBasisTotalAmount']").asString().isEqualTo("41.94");
		assertThat(theXML).valueByXPath(
			"//*[local-name()='ApplicableHeaderTradeSettlement']//*[local-name()='SpecifiedTradeSettlementHeaderMonetarySummation']//*[local-name()='GrandTotalAmount']").asString().isEqualTo("44.91");
	}

}
