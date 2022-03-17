package com.udemy.pki.service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;

import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfDate;
import com.itextpdf.text.pdf.PdfDictionary;
import com.itextpdf.text.pdf.PdfName;
import com.itextpdf.text.pdf.PdfPKCS7;
import com.itextpdf.text.pdf.PdfSignature;
import com.itextpdf.text.pdf.PdfSignatureAppearance;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.PdfString;
import com.udemy.pki.model.CertificateData;
import com.udemy.pki.reader.AutocloseablePdfReader;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

public class PdfSignatureService implements SignatureService {
    @Override
    public void sign(CertificateData certificateData) {
        byte[] documentContent;
        try {
            Path path = Paths.get("C:\\documents\\test.pdf");
            documentContent = Files.readAllBytes(path);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        byte[] signatureResult = this.sign(documentContent, certificateData);

        try(FileOutputStream out = new FileOutputStream("C:\\documents\\test_signed.pdf")) {
            out.write(signatureResult);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        System.out.println("pdf signed");
    }

    @Override
	public byte[] sign(byte[] documentContent, CertificateData certificateData) {
        try(AutocloseablePdfReader pdfReader = new AutocloseablePdfReader(documentContent);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            PdfStamper pdfStamper = PdfStamper.createSignature(pdfReader, outputStream, '\000', null, true);
            PdfSignatureAppearance pdfSignatureAppearance = PdfSignatureService.getPdfSignatureAppearance(certificateData, pdfStamper);

            int estimatedContent = 8192;
            System.out.println(pdfReader.getFileLength());
            HashMap<PdfName, Integer> exclusionSizes = new HashMap<>();
            exclusionSizes.put(PdfName.CONTENTS, Integer.valueOf(estimatedContent * 2 + 2));
            pdfSignatureAppearance.preClose(exclusionSizes);

            byte[] hashBytes = PdfSignatureService.getHashBytes(pdfSignatureAppearance, estimatedContent);
            Calendar signatureCalendar = pdfSignatureAppearance.getSignDate();

            PdfPKCS7 sgn = new PdfPKCS7(certificateData.privateKey(), certificateData.certificateChain(), null, "SHA-256", null, false);
            byte[] sh = sgn.getAuthenticatedAttributeBytes(hashBytes, signatureCalendar, null);
            sgn.update(sh, 0, sh.length);
            byte[] encodedSig = sgn.getEncodedPKCS7(hashBytes, signatureCalendar, null, null);

            byte[] paddedSig = new byte[estimatedContent];
            System.arraycopy(encodedSig, 0, paddedSig, 0, encodedSig.length);
            PdfDictionary pdfDic = new PdfDictionary();
            pdfDic.put(PdfName.CONTENTS, new PdfString(paddedSig).setHexWriting(true));
            pdfSignatureAppearance.close(pdfDic);

            outputStream.flush();

            byte[] result = outputStream.toByteArray();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
	}

	private static Calendar getCalendarFromDate(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);

        return calendar;
    }

    private static PdfSignatureAppearance getPdfSignatureAppearance(CertificateData certificateData, PdfStamper pdfStamper) throws CertificateEncodingException {
        Date currentDate = new Date();
        Calendar currentCalendar = PdfSignatureService.getCalendarFromDate(currentDate);
        String commonName = PdfSignatureService.getCNFromPublicCertificate(certificateData.publicCertificate());
        String reason = "Digital Signature to ensure authentication, integrity and non-repudiation";
        String location = "Madrid";
        SimpleDateFormat dateFormatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss Z");
        String formattedCurrentDate = dateFormatter.format(currentDate);
        String signature = "Signed by " + commonName + "\nReason: " + reason + "\nLocation: " + location + "\nFecha: " + formattedCurrentDate;;

        PdfSignature pdfSignature = new PdfSignature(PdfName.ADOBE_PPKLITE, new PdfName("adbe.pkcs7.detached"));
        pdfSignature.setReason(reason);
        pdfSignature.setLocation(location);
        pdfSignature.setDate(new PdfDate(currentCalendar));

        PdfSignatureAppearance pdfSignatureAppearance = pdfStamper.getSignatureAppearance();
        pdfSignatureAppearance.setSignDate(currentCalendar);
        pdfSignatureAppearance.setCryptoDictionary(pdfSignature);
        pdfSignatureAppearance.setLayer2Text(signature);
        pdfSignatureAppearance.setVisibleSignature(new Rectangle(100, 100, 350, 200), 1, null);

        return pdfSignatureAppearance;
    }

    private static String getCNFromPublicCertificate(X509Certificate publicCertificate) throws CertificateEncodingException {
        X500Name x500name = new JcaX509CertificateHolder(publicCertificate).getSubject();
        RDN cn = x500name.getRDNs(BCStyle.CN)[0];

        return IETFUtils.valueToString(cn.getFirst().getValue());
    }

    private static byte[] getHashBytes(PdfSignatureAppearance pdfSignatureAppearance, int estimatedContent) throws IOException, NoSuchAlgorithmException {
        InputStream rangeStream = pdfSignatureAppearance.getRangeStream();
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[estimatedContent];
        int length;
        while ((length = rangeStream.read(buffer)) > 0) {
            messageDigest.update(buffer, 0, length);
        }

        byte[] hashBytes = messageDigest.digest();
        return hashBytes;
    }
}