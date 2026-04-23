package org.mustangproject.validator;

import static org.xmlunit.assertj.XmlAssert.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;

import org.mustangproject.Allowance;
import org.mustangproject.BankDetails;
import org.mustangproject.Contact;
import org.mustangproject.Invoice;
import org.mustangproject.Item;
import org.mustangproject.Product;
import org.mustangproject.TradeParty;
import org.mustangproject.ZUGFeRD.LineCalculator;
import org.mustangproject.ZUGFeRD.Profiles;
import org.mustangproject.ZUGFeRD.TransactionCalculator;
import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;

import junit.framework.TestCase;

/**
 * Pairs with gross-inclusive tests in {@code org.mustangproject.ZUGFeRD.CalculationTest} (with and without line discount),
 * plus {@link ZUGFeRDValidator} on the generated CII.
 * <p>
 * This class lives in the {@code validator} module because a {@code library} test dependency on {@code validator}
 * would create a Maven reactor cycle. Run for example:
 * {@code mvn test -pl validator -am -Dtest=org.mustangproject.validator.GrossInclusiveXRechnungCIIValidationTest -Dsurefire.failIfNoSpecifiedTests=false}
 * from the repository root (the {@code -am} / {@code failIfNoSpecifiedTests} flags keep reactor + surefire happy).
 */
public class GrossInclusiveXRechnungCIIValidationTest extends TestCase {

	public void testGrossInclusiveVatPercentDiscountXmlValidatesWithZugferdValidator() throws Exception {
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
		Item item = new Item(product, BigDecimal.ZERO, new BigDecimal("9")).setGrossPrice(new BigDecimal("4.99"));

		LineCalculator lc = item.getCalculation();
		assertEquals(0, new BigDecimal("36.54").compareTo(lc.getItemTotalNetAmount()));
		assertEquals(0, new BigDecimal("2.52").compareTo(lc.getItemTotalVATAmount()));

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
		assertEquals(0, new BigDecimal("39.06").compareTo(tc.getGrandTotal()));

		ZUGFeRD2PullProvider zf2p = new ZUGFeRD2PullProvider();
		zf2p.setProfile(Profiles.getByName("XRechnung"));
		zf2p.generateXML(invoice);
		byte[] xmlBytes = zf2p.getXML();

		ZUGFeRDValidator zfValidator = new ZUGFeRDValidator();
		String result = zfValidator.validate(xmlBytes, "xrechnung-" + invoice.getNumber() + ".xml");

		assertThat(result).valueByXPath("/validation/xml/summary/@status").isEqualTo("valid");
		assertThat(result).valueByXPath("/validation/summary/@status").isEqualTo("valid");

		String theXML = new String(xmlBytes, StandardCharsets.UTF_8);
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		Document doc = dbf.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(theXML.getBytes(StandardCharsets.UTF_8)));
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
		assertEquals(0, new BigDecimal(grossStr).subtract(new BigDecimal(allowanceStr)).compareTo(new BigDecimal(netStr)));
	}

	/** Pairs with {@code CalculationTest#testGrossInclusiveNoDiscountXmlAndTotals}: list gross 4.99 € / unit, no allowances. */
	public void testGrossInclusiveNoDiscountXmlValidatesWithZugferdValidator() throws Exception {
		SimpleDateFormat sqlDate = new SimpleDateFormat("yyyy-MM-dd");
		TradeParty recipient = new TradeParty("Buyer GmbH", "Käuferweg 2", "80331", "München", "DE");
		recipient.setID("991-01484-64");
		recipient.setEmail("buyer@example.org");
		recipient.addVATID("DE999999999");
		Product product = new Product("Artikel ohne Rabatt", "", "H87", new BigDecimal("7"));
		Item item = new Item(product, new BigDecimal("4.66"), new BigDecimal("9")).setGrossPrice(new BigDecimal("4.99"));

		LineCalculator lc = item.getCalculation();
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
		assertEquals(0, new BigDecimal("44.91").compareTo(tc.getGrandTotal()));

		ZUGFeRD2PullProvider zf2p = new ZUGFeRD2PullProvider();
		zf2p.setProfile(Profiles.getByName("XRechnung"));
		zf2p.generateXML(invoice);
		byte[] xmlBytes = zf2p.getXML();

		ZUGFeRDValidator zfValidator = new ZUGFeRDValidator();
		String result = zfValidator.validate(xmlBytes, "xrechnung-" + invoice.getNumber() + ".xml");

		assertThat(result).valueByXPath("/validation/xml/summary/@status").isEqualTo("valid");
		assertThat(result).valueByXPath("/validation/summary/@status").isEqualTo("valid");
	}

}
