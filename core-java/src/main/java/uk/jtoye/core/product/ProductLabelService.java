package uk.jtoye.core.product;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProductLabelService {

    private static final String[] ALLERGEN_NAMES = {
            "Gluten", "Crustaceans", "Eggs", "Fish", "Peanuts", "Soybeans",
            "Milk", "Nuts", "Celery", "Mustard", "Sesame", "Sulphites", "Lupin", "Molluscs"
    };

    private final ProductRepository productRepository;

    public ProductLabelService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public byte[] generateLabel(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Label size: 100mm x 60mm
        Document doc = new Document(new Rectangle(283, 170), 10, 10, 10, 10);
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font skuFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Font.ITALIC);
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font allergenFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);

        // Product name
        Paragraph title = new Paragraph(product.getTitle(), titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        doc.add(title);

        // SKU
        Paragraph sku = new Paragraph(product.getSku(), skuFont);
        sku.setAlignment(Element.ALIGN_CENTER);
        sku.setSpacingAfter(5);
        doc.add(sku);

        // Price
        if (product.getPricePennies() != null) {
            Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Paragraph price = new Paragraph(
                    String.format("£%.2f", product.getPricePennies() / 100.0), priceFont);
            price.setAlignment(Element.ALIGN_CENTER);
            price.setSpacingAfter(5);
            doc.add(price);
        }

        // Ingredients
        doc.add(new Paragraph("Ingredients:", sectionFont));
        Paragraph ingredients = new Paragraph(product.getIngredientsText(), bodyFont);
        ingredients.setSpacingAfter(5);
        doc.add(ingredients);

        // Allergens
        int mask = product.getAllergenMask();
        if (mask != 0) {
            doc.add(new Paragraph("CONTAINS:", allergenFont));
            StringBuilder allergens = new StringBuilder();
            for (int i = 0; i < ALLERGEN_NAMES.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    if (allergens.length() > 0) allergens.append(", ");
                    allergens.append(ALLERGEN_NAMES[i].toUpperCase());
                }
            }
            Paragraph allergenText = new Paragraph(allergens.toString(), allergenFont);
            doc.add(allergenText);
        } else {
            doc.add(new Paragraph("No allergens declared", bodyFont));
        }

        doc.close();
        return out.toByteArray();
    }
}
